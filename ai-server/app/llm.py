"""LLM(OpenAI) 보조 기능 — 판정에는 쓰지 않는다.

1. 보완 안내 문구 다듬기: Jev는 글을 만들지 않으므로, 템플릿으로 만든 안내 초안을 구성원이 읽기 좋게 다듬는다.
2. 규정 조건 초안 생성: 규정 관리자가 새 규정집을 올릴 때 Jev에 물을 질문(judgments) 초안을 제안한다.
모든 요청은 성명 등 개인정보를 마스킹한 뒤 보낸다.
"""
from __future__ import annotations

import json

import httpx

from .config import get_settings
from .privacy.masking import Masker


class LlmUnavailable(RuntimeError):
    pass


async def _chat(messages: list[dict], json_mode: bool = False, max_tokens: int = 900) -> str:
    s = get_settings()
    if not s.openai_api_key:
        raise LlmUnavailable("OPENAI_API_KEY가 설정되지 않아 LLM 기능을 쓸 수 없습니다.")
    body: dict = {"model": s.openai_chat_model, "temperature": 0.2, "max_tokens": max_tokens, "messages": messages}
    if json_mode:
        body["response_format"] = {"type": "json_object"}
    async with httpx.AsyncClient(timeout=s.openai_timeout_seconds) as c:
        r = await c.post(f"{s.openai_base_url}/chat/completions", headers={"Authorization": f"Bearer {s.openai_api_key}"}, json=body)
        if r.status_code >= 400:
            raise LlmUnavailable(f"OpenAI 오류 HTTP {r.status_code}: {r.text[:300]}")
        return r.json()["choices"][0]["message"]["content"]


async def polish_guidance(draft: str, verdict: str, pii_names: list[str]) -> str:
    masker = Masker()
    masker.register_names(pii_names)
    masked = masker.scrub(draft)
    system = ("당신은 회사 HR 담당자를 돕는 문서 작성 보조자입니다. 주어진 '보완 안내 초안'의 사실관계(금액, 날짜, 조항, "
              "필요 서류)는 한 글자도 바꾸지 말고, 구성원이 읽기 쉽고 정중한 한국어 안내문으로 다듬으세요. 새로운 사유나 "
              "요구 사항을 지어내지 마세요. 대괄호로 표시된 토큰([이름A] 등)은 그대로 두세요. 5문장 안팎의 평문으로 쓰세요.")
    out = await _chat([{"role": "system", "content": system},
                       {"role": "user", "content": f"판정: {verdict}\n\n보완 안내 초안:\n{masked}"}])
    return masker.unmask(out.strip())


async def draft_judgments(support_type: str, regulation_text: str) -> dict:
    system = (
        "당신은 사내 복리후생 규정을 '결정 모델이 답할 질문'으로 바꾸는 규정 분석가입니다. 규정 조항 중 금액 비교·날짜 계산처럼 "
        "코드로 확정할 수 있는 조건은 static_rules 후보로, 의미 판단이 필요한 조건(기관 유형, 비용 성격, 목적 등)은 judgments "
        "후보로 나누세요. judgments의 type은 noul(예/아니오), choice(보기 중 하나), score(순서 있는 수준) 중 하나입니다. "
        "한 질문이 다른 질문의 답에 기대야 하면 depends_on에 그 id를 적으세요. 다음 JSON 형식으로만 답하세요: "
        '{"judgments":[{"id":"J-...","label":"...","type":"noul|choice|score","clause_refs":["제N조"],"instructions":"...",'
        '"criteria":{},"pass_options":[],"depends_on":[]}],"static_rule_candidates":[{"label":"...","clause":"제N조","description":"..."}]}'
    )
    out = await _chat([{"role": "system", "content": system},
                       {"role": "user", "content": f"지원 종류: {support_type}\n\n규정집:\n{regulation_text[:12000]}"}],
                      json_mode=True, max_tokens=2500)
    return json.loads(out)
