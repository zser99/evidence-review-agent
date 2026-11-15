"""규정 조건 판정 오케스트레이션.

흐름:  조건(YAML) → 필요한 사실값만 추출 → 개인정보 마스킹 → 근거 조항 검색(Qdrant)
      → 의존 관계에 따라 단계(phase)로 나눠 결정 모델에 질의 → 충족 확률·확신도 계산
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from typing import Any

from ..config import get_settings
from ..privacy.masking import Masker
from ..rag.store import get_store
from ..schemas import JudgeRequest, JudgmentSpec
from .base import Answer, DecisionError, DecisionProvider, Question
from .jev import JevProvider
from .local import LocalProvider
from .openai_logprob import OpenAILogprobProvider

log = logging.getLogger("judge")
MISSING_TEXT = "확인 안 됨"
MAX_VALUE_LEN = 200


def provider_kind() -> str:
    s = get_settings()
    if s.decision_provider != "auto":
        return s.decision_provider
    if s.jev_api_key:
        return "jev"
    if s.openai_api_key:
        return "openai"
    return "local"


def get_provider() -> DecisionProvider:
    return {"jev": JevProvider, "openai": OpenAILogprobProvider, "local": LocalProvider}[provider_kind()]()


def resolve(facts: Any, path: str) -> Any:
    """'ev.문서.표.열'처럼 점으로 이은 경로를 따라간다. 목록을 만나면 남은 경로를 각 원소에 적용한다."""
    cur = facts
    parts = path.split(".")
    for i, p in enumerate(parts):
        if isinstance(cur, list):
            rest = ".".join(parts[i:])
            vals = [resolve(x, rest) for x in cur]
            return [v for v in vals if v is not None] or None
        if not isinstance(cur, dict) or p not in cur:
            return None
        cur = cur[p]
    return cur


def _render(value: Any, ftype: str | None) -> str | None:
    if value is None or value == "" or value == []:
        return None
    if isinstance(value, list):
        return ", ".join(str(v) for v in value)
    if ftype == "money" and isinstance(value, (int, float)):
        return f"{int(value):,}원"
    return re.sub(r"\s+", " ", str(value)).strip()[:MAX_VALUE_LEN]


def _fill(template: str, values: dict[str, str]) -> str:
    return re.sub(r"\{([^{}]+)\}", lambda m: str(values.get(m.group(1), "")), template)


def _phases(specs: list[JudgmentSpec]) -> list[list[JudgmentSpec]]:
    by_id = {s.id: s for s in specs}
    level: dict[str, int] = {}

    def lv(sid: str, seen: tuple = ()) -> int:
        if sid in level:
            return level[sid]
        if sid in seen:
            raise ValueError(f"조건 '{sid}'의 depends_on이 순환합니다.")
        deps = [d for d in by_id[sid].depends_on if d in by_id]
        level[sid] = 0 if not deps else 1 + max(lv(d, seen + (sid,)) for d in deps)
        return level[sid]

    for s in specs:
        lv(s.id)
    out: list[list[JudgmentSpec]] = [[] for _ in range(max(level.values(), default=-1) + 1)]
    for s in specs:
        out[level[s.id]].append(s)
    return out


def interpret(spec: JudgmentSpec, ans: Answer) -> dict:
    """모델의 확률 분포를 '조건 충족 확률'과 '확신도'로 바꾼다.

    확신도 = max(P(충족), 1 − P(충족)) — 내려진 충족/미충족 판정이 맞을 확률.
    보정(calibration) 검증은 이 값의 구간별 실제 정답률로 한다.
    """
    p = ans.probabilities
    if spec.type == "noul":
        p_pass = p["true"] if spec.pass_when else p["false"]
        answer = "true" if p["true"] >= 0.5 else "false"
        label = "예" if answer == "true" else "아니오"
    elif spec.type == "choice":
        passing = spec.pass_options or []
        p_pass = sum(v for k, v in p.items() if k in passing)
        answer = ans.choice or max(p, key=p.get)
        label = spec.option_labels.get(answer, answer)
    else:
        n = len(spec.criteria)
        min_level = spec.pass_min_level if spec.pass_min_level is not None else n - 1
        p_pass = sum(v for k, v in p.items() if int(k) >= min_level)
        answer = round(ans.score if ans.score is not None else sum(int(k) * v for k, v in p.items()), 3)
        label = f"{answer:.2f} / {n - 1}"
    p_pass = min(1.0, max(0.0, p_pass))
    return {"answer": answer, "answer_label": label, "satisfied": p_pass >= 0.5,
            "pass_probability": round(p_pass, 4), "confidence": round(max(p_pass, 1 - p_pass), 4),
            "native_confidence": ans.native_confidence, "probabilities": {k: round(v, 4) for k, v in p.items()}}


async def judge(req: JudgeRequest, provider: DecisionProvider | None = None) -> dict:
    t0 = time.time()
    provider = provider or get_provider()
    store = get_store()
    masker = Masker()
    masker.register_names(req.pii_names)
    facts = dict(req.facts)
    facts.setdefault("judgments", {})
    results: dict[str, dict] = {}
    warnings: list[str] = []
    meta_all: list[dict] = []
    hashes: list[str] = []

    for phase_no, specs in enumerate(_phases(req.judgments)):
        questions: list[Question] = []
        state_info: dict[str, dict] = {}
        clause_union: dict[str, dict] = {}
        for spec in specs:
            base = {"id": spec.id, "label": spec.label, "type": spec.type, "phase": phase_no + 1,
                    "clause_refs": spec.clause_refs, "criteria": spec.criteria}
            bad_dep = [d for d in spec.depends_on if results.get(d, {}).get("status") != "ok"]
            if bad_dep:
                results[spec.id] = {**base, "status": "skipped", "detail": f"선행 판정({', '.join(bad_dep)})이 완료되지 않아 판정하지 않았습니다."}
                continue
            raw_inputs: dict[str, str | None] = {}
            for name, path in spec.inputs.items():
                ftype = req.field_types.get(path)
                v = masker.mask(resolve(facts, path), ftype)
                raw_inputs[name] = _render(v, ftype)
            required = spec.required_inputs or []
            missing_required = [n for n in required if raw_inputs.get(n) is None]
            if missing_required or (raw_inputs and all(v is None for v in raw_inputs.values())):
                names = missing_required or list(raw_inputs)
                results[spec.id] = {**base, "status": "skipped", "inputs": {k: v or MISSING_TEXT for k, v in raw_inputs.items()},
                                    "detail": f"판정에 필요한 값({', '.join(names)})을 증빙에서 읽지 못했습니다."}
                continue
            inputs = {k: (v if v is not None else MISSING_TEXT) for k, v in raw_inputs.items()}
            query = spec.instructions + " " + " ".join(inputs.values())
            try:
                clauses = await store.retrieve(req.support_type, req.policy_version, query, spec.clause_refs, get_settings().rag_top_k)
            except Exception as e:  # noqa: BLE001
                log.warning("규정 검색 실패: %s", e)
                clauses = []
            if not any(c["source"] == "지정 조항" for c in clauses) and spec.clause_refs:
                warnings.append(f"{spec.id}: 근거 조항({', '.join(spec.clause_refs)})이 색인에 없습니다. 규정을 다시 색인하세요.")
            for c in clauses:
                clause_union.setdefault(c["clause_id"], c)
            target = "\n".join(f"- {k}: {v}" for k, v in inputs.items())
            refs = ", ".join(f"{c['clause_id']}({c['title']})" for c in clauses if c["source"] == "지정 조항") or ", ".join(spec.clause_refs)
            instructions = f"{spec.instructions}\n\n[판단 대상]\n{target}\n[근거 조항] {refs}"
            hints = {"local_text": _fill(spec.local_text, {k: (v or "") for k, v in raw_inputs.items()}) if spec.local_text else None,
                     "examples": spec.examples, "local_features": spec.local_features, "inputs": inputs}
            questions.append(Question(spec.id, spec.type, instructions, spec.criteria, hints))
            state_info[spec.label or spec.id] = inputs
            results[spec.id] = {**base, "status": "pending", "question": instructions, "inputs": inputs, "clauses": clauses}
        if not questions:
            continue
        state = {
            "지원 종류": req.support_name or req.support_type,
            "규정 버전": req.policy_version,
            "적용 규정 조항": [{"조항": f"{c['clause_id']}({c['title']})", "내용": c["text"]} for c in clause_union.values()],
            "판단 대상 정보": state_info,
        }
        canon = json.dumps({"model": provider.model, "state": state,
                            "questions": {q.id: {"type": q.type, "instructions": q.instructions, "criteria": q.criteria} for q in questions}},
                           ensure_ascii=False, sort_keys=True)
        hashes.append(hashlib.sha256(canon.encode("utf-8")).hexdigest())
        try:
            answers, meta = await provider.decide(state, questions)
            meta_all.append({"phase": phase_no + 1, **meta})
        except DecisionError as e:
            log.error("결정 모델 호출 실패(phase %d): %s", phase_no + 1, e)
            for q in questions:
                results[q.id].update({"status": "error", "detail": f"결정 모델 호출에 실패했습니다: {e}"})
            continue
        by_id = {s.id: s for s in specs}
        for q in questions:
            r = interpret(by_id[q.id], answers[q.id])
            results[q.id].update({"status": "ok", **r})
            facts["judgments"][q.id] = {"answer": r["answer"], "answer_label": r["answer_label"],
                                        "satisfied": r["satisfied"], "confidence": r["confidence"]}

    ordered = [results[s.id] for s in req.judgments]
    model = next((m.get("model") for m in meta_all if m.get("model")), provider.model)
    return {
        "provider": provider.name, "requested_model": provider.model, "model": model,
        "phases": len(hashes), "request_hashes": hashes, "calls": meta_all, "warnings": warnings,
        "masked_names": len(masker.mapping), "elapsed_ms": int((time.time() - t0) * 1000), "results": ordered,
    }
