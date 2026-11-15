"""결정 모델 공통 타입. Jev의 세 질문 유형(noul · choice · score)을 그대로 따른다."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol


@dataclass
class Question:
    id: str
    type: str                      # noul | choice | score
    instructions: str
    criteria: Any = None           # noul: {"true","false"} · choice: {옵션: 설명} · score: [낮은 수준 → 높은 수준]
    hints: dict = field(default_factory=dict)  # 로컬 대체 모델용(local_text, examples, local_features, inputs)

    def options(self) -> list[str]:
        if self.type == "noul":
            return ["true", "false"]
        if self.type == "choice":
            return list(self.criteria.keys())
        return [str(i) for i in range(len(self.criteria))]


@dataclass
class Answer:
    id: str
    type: str
    probabilities: dict[str, float]      # 옵션(또는 수준 번호) → 확률, 합 1
    choice: str | None = None            # choice: 선택된 옵션
    score: float | None = None           # score: 확률 가중 평균 수준
    native_confidence: float | None = None  # Jev가 돌려준 confidence(분포 집중도). noul에는 없다.
    raw: dict | None = None


class DecisionProvider(Protocol):
    name: str
    model: str

    async def decide(self, state: Any, questions: list[Question]) -> tuple[dict[str, Answer], dict]:
        """(질문 id → 답, 메타 정보)"""
        ...


class DecisionError(RuntimeError):
    pass


def normalize(probs: dict[str, float]) -> dict[str, float]:
    total = sum(max(0.0, v) for v in probs.values())
    if total <= 0:
        n = len(probs) or 1
        return {k: 1.0 / n for k in probs}
    return {k: max(0.0, v) / total for k, v in probs.items()}


def concentration(probs: dict[str, float]) -> float:
    """분포 집중도(0=균등, 1=한 옵션에 몰림). Jev confidence와 같은 뜻의 값을 대체 모델에서도 채운다."""
    k = len(probs)
    if k <= 1:
        return 1.0
    return max(0.0, (k * max(probs.values()) - 1) / (k - 1))
