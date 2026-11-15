from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


class JudgmentSpec(BaseModel):
    """지원 종류 YAML의 judgments[*] 한 항목."""
    id: str
    label: str = ""
    type: Literal["noul", "choice", "score"]
    instructions: str
    criteria: Any = None
    clause_refs: list[str] = Field(default_factory=list)
    inputs: dict[str, str] = Field(default_factory=dict)        # 표시 이름 → facts 경로
    required_inputs: list[str] = Field(default_factory=list)
    depends_on: list[str] = Field(default_factory=list)
    pass_options: list[str] | None = None                       # choice: 충족으로 보는 옵션
    pass_min_level: int | None = None                           # score: 충족으로 보는 최소 수준
    pass_when: bool = True                                      # noul: 충족으로 보는 답
    option_labels: dict[str, str] = Field(default_factory=dict)
    local_text: str | None = None
    examples: dict[str, list[str]] | None = None
    local_features: dict | None = None
    model_config = {"extra": "ignore"}


class JudgeRequest(BaseModel):
    support_type: str
    support_name: str = ""
    policy_version: str
    facts: dict[str, Any]
    field_types: dict[str, str] = Field(default_factory=dict)   # facts 경로 → 항목 유형(person_name 등)
    pii_names: list[str] = Field(default_factory=list)
    judgments: list[JudgmentSpec]


class IndexRequest(BaseModel):
    support_type: str
    version: str
    effective_date: str = ""
    regulation_text: str


class SearchRequest(BaseModel):
    support_type: str
    version: str
    query: str
    top_k: int = 5


class CalibrationSample(BaseModel):
    confidence: float
    correct: bool
    group: str | None = None


class CalibrateRequest(BaseModel):
    samples: list[CalibrationSample]
    target_accuracy: float = 0.97
    bins: list[float] = Field(default_factory=lambda: [0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 1.0])
    min_samples: int = 5


class PolishRequest(BaseModel):
    draft: str
    verdict: str = "REJECT"
    pii_names: list[str] = Field(default_factory=list)


class DraftRequest(BaseModel):
    support_type: str
    regulation_text: str
