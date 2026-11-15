"""TypeSafe Jev 결정 모델 클라이언트.

API 규격(https://docs.aimlapi.com/api-references/decision-models/typesafe/jev):
  POST {base}/v1/decisions   Authorization: Bearer <key>
  요청  {"model", "state", "questions": {키: {"type": noul|choice|score, "instructions", "criteria"}}}
  응답  {"model", "answers": {키: {"type":"noul","noul":p} | {"type":"choice","choice","confidence","probabilities"}
                              | {"type":"score","score","confidence","legend","probabilities"}}, "usage"}
한 요청 안의 질문들은 서로의 답을 볼 수 없으므로, 앞 판정에 기대는 질문은 호출 쪽(service)에서 요청을 나눈다.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

import httpx

from ..config import get_settings
from .base import Answer, DecisionError, Question, normalize

log = logging.getLogger("jev")


class JevProvider:
    name = "jev"

    def __init__(self) -> None:
        s = get_settings()
        self.model = s.jev_model
        self.url = s.jev_base_url.rstrip("/") + s.jev_path
        self.key = s.jev_api_key
        self.timeout = s.jev_timeout_seconds

    def build_request(self, state: Any, questions: list[Question]) -> dict:
        qs: dict[str, dict] = {}
        for q in questions:
            item: dict[str, Any] = {"type": q.type, "instructions": q.instructions}
            if q.criteria is not None:
                item["criteria"] = q.criteria
            qs[q.id] = item
        return {"model": self.model, "state": state, "questions": qs}

    @staticmethod
    def parse_answers(body: dict, questions: list[Question]) -> dict[str, Answer]:
        answers = body.get("answers") or {}
        out: dict[str, Answer] = {}
        for q in questions:
            a = answers.get(q.id)
            if not isinstance(a, dict):
                raise DecisionError(f"Jev 응답에 질문 '{q.id}'의 답이 없습니다.")
            if q.type == "noul":
                p = float(a["noul"])
                out[q.id] = Answer(q.id, "noul", {"true": p, "false": 1.0 - p}, raw=a)
            elif q.type == "choice":
                probs = normalize({k: float(v) for k, v in (a.get("probabilities") or {}).items()})
                for opt in q.options():
                    probs.setdefault(opt, 0.0)
                out[q.id] = Answer(q.id, "choice", probs, choice=a.get("choice"),
                                   native_confidence=a.get("confidence"), raw=a)
            else:
                probs = normalize({str(k): float(v) for k, v in (a.get("probabilities") or {}).items()})
                for opt in q.options():
                    probs.setdefault(opt, 0.0)
                out[q.id] = Answer(q.id, "score", probs, score=float(a["score"]),
                                   native_confidence=a.get("confidence"), raw=a)
        return out

    async def decide(self, state: Any, questions: list[Question]) -> tuple[dict[str, Answer], dict]:
        if not self.key:
            raise DecisionError("JEV_API_KEY가 설정되지 않았습니다.")
        payload = self.build_request(state, questions)
        last: Exception | None = None
        async with httpx.AsyncClient(timeout=self.timeout) as c:
            for attempt in range(3):
                try:
                    r = await c.post(self.url, json=payload, headers={"Authorization": f"Bearer {self.key}",
                                                                      "Content-Type": "application/json"})
                    if r.status_code in (429, 500, 502, 503, 504):
                        raise DecisionError(f"Jev 일시 오류 HTTP {r.status_code}: {r.text[:200]}")
                    if r.status_code >= 400:
                        # 4xx는 재시도해도 같으므로 바로 실패
                        raise httpx.HTTPStatusError(f"Jev 요청 오류 HTTP {r.status_code}: {r.text[:300]}",
                                                    request=r.request, response=r)
                    body = r.json()
                    meta = {"provider": self.name, "requested_model": self.model, "model": body.get("model", self.model),
                            "usage": body.get("usage"), "meta": body.get("meta")}
                    return self.parse_answers(body, questions), meta
                except httpx.HTTPStatusError as e:
                    raise DecisionError(str(e)) from e
                except (DecisionError, httpx.TransportError) as e:
                    last = e
                    log.warning("Jev 호출 재시도 %d/3: %s", attempt + 1, e)
                    await asyncio.sleep(0.6 * (attempt + 1))
        raise DecisionError(f"Jev 호출 실패: {last}")
