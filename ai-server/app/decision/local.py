"""Jev 접근이 어려울 때의 대체 모델 ②: 외부 호출이 없는 소형 분류기.

지원 종류 YAML의 조건마다 적어 둔 예시(examples)를 근거로, 판단 대상 문장과 예시의 TF-IDF 유사도를
옵션별 점수로 삼고 온도(temperature) 소프트맥스로 확률을 만든다. 온도는 정답 세트로 보정한다.
코드가 아니라 설정(예시)만으로 동작하므로 지원 종류를 더해도 고칠 것이 없다.
결정적(같은 입력 → 같은 답)이며, 예시와 닮지 않은 입력은 옵션 간 점수 차가 작아 확신도가 낮게 나온다.
"""
from __future__ import annotations

import math
import re
from collections import Counter
from typing import Any

from ..config import get_settings
from .base import Answer, DecisionError, Question, concentration, normalize

MISSING_TOKENS = {"", "확인 안 됨", "없음", "None"}


def _features(text: str) -> Counter:
    """띄어쓰기에 영향받지 않도록(OCR은 공백을 자주 놓친다) 공백을 지운 문자 n-gram만 쓴다."""
    compact = re.sub(r"[^\w가-힣]", "", str(text).lower())
    c: Counter = Counter()
    for n in (2, 3, 4):
        for i in range(len(compact) - n + 1):
            c[f"c{n}:" + compact[i:i + n]] += 1.0
    if len(compact) == 1:
        c["c1:" + compact] += 1.0
    return c


def _cosine(a: dict, b: dict) -> float:
    if not a or not b:
        return 0.0
    dot = sum(v * b.get(k, 0.0) for k, v in a.items())
    na = math.sqrt(sum(v * v for v in a.values()))
    nb = math.sqrt(sum(v * v for v in b.values()))
    return dot / (na * nb) if na and nb else 0.0


class LocalProvider:
    name = "local-exemplar"
    model = "tfidf-exemplar-v1"

    def _classify(self, text: str, examples: dict[str, list[str]], options: list[str]) -> dict[str, float]:
        docs = [(opt, ex, _features(ex)) for opt in options for ex in examples.get(opt, [])]
        if not docs:
            raise DecisionError("로컬 대체 모델에는 조건별 examples가 필요합니다.")
        n = len(docs)
        df: Counter = Counter()
        for _, _, f in docs:
            df.update(f.keys())
        idf = {k: math.log((n + 1) / (v + 1)) + 1.0 for k, v in df.items()}

        def weigh(f: Counter) -> dict:
            return {k: (1 + math.log(v)) * idf.get(k, math.log(n + 1) + 1.0) for k, v in f.items()}

        q = weigh(_features(text))
        scores: dict[str, float] = {}
        for opt in options:
            sims = sorted((_cosine(q, weigh(f)) for o, _, f in docs if o == opt), reverse=True)
            scores[opt] = (0.7 * sims[0] + 0.3 * (sum(sims[:2]) / len(sims[:2]))) if sims else 0.0
        temp = max(1e-3, get_settings().local_temperature)
        m = max(scores.values())
        return normalize({k: math.exp((v - m) / temp) for k, v in scores.items()})

    @staticmethod
    def _presence_score(features: dict, inputs: dict, levels: int) -> dict[str, float]:
        req = features.get("required_present", [])
        missing = sum(1 for k in req if str(inputs.get(k, "")).strip() in MISSING_TOKENS)
        level = max(0, levels - 1 - missing)
        probs = {str(i): 0.0 for i in range(levels)}
        probs[str(level)] = 0.97
        neighbors = [i for i in (level - 1, level + 1) if 0 <= i < levels]
        for i in neighbors:
            probs[str(i)] = 0.03 / len(neighbors)
        return normalize(probs)

    async def decide(self, state: Any, questions: list[Question]) -> tuple[dict[str, Answer], dict]:
        out: dict[str, Answer] = {}
        for q in questions:
            h = q.hints or {}
            opts = q.options()
            if q.type == "score" and h.get("local_features"):
                probs = self._presence_score(h["local_features"], h.get("inputs", {}), len(opts))
            else:
                probs = self._classify(h.get("local_text") or q.instructions, {str(k): v for k, v in (h.get("examples") or {}).items()}, opts)
            raw = {"probabilities": probs, "local_text": h.get("local_text")}
            if q.type == "choice":
                out[q.id] = Answer(q.id, "choice", probs, choice=max(probs, key=probs.get),
                                   native_confidence=concentration(probs), raw=raw)
            elif q.type == "score":
                out[q.id] = Answer(q.id, "score", probs, score=sum(int(k) * p for k, p in probs.items()),
                                   native_confidence=concentration(probs), raw=raw)
            else:
                out[q.id] = Answer(q.id, "noul", probs, raw=raw)
        return out, {"provider": self.name, "requested_model": self.model, "model": self.model,
                     "temperature": get_settings().local_temperature}
