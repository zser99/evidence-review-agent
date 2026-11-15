"""AI 서버 핵심 로직 테스트. OCR 모델·외부 API 없이 돈다(텍스트 PDF, 로컬 분류기, in-memory Qdrant, 모의 HTTP)."""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import httpx
import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from app import calibration  # noqa: E402
from app.decision import service  # noqa: E402
from app.decision.base import Question  # noqa: E402
from app.decision.jev import JevProvider  # noqa: E402
from app.decision.local import LocalProvider  # noqa: E402
from app.ocr.engine import load_document  # noqa: E402
from app.ocr.extract import extract  # noqa: E402
from app.ocr.normalize import mask_rrn, parse_date, parse_money, parse_semester  # noqa: E402
from app.privacy.masking import Masker  # noqa: E402
from app.rag.chunker import split_clauses  # noqa: E402
from app.rag.store import RegulationStore  # noqa: E402
from app.schemas import JudgeRequest  # noqa: E402

POLICY = {st: yaml.safe_load((ROOT / "seed/policies" / st / "policy.yaml").read_text(encoding="utf-8")) for st in ("tuition", "medical")}
REGULATION = {st: (ROOT / "seed/policies" / st / "regulation.md").read_text(encoding="utf-8") for st in ("tuition", "medical")}


def doc_spec(st: str, doc_id: str) -> dict:
    return next(d for d in POLICY[st]["documents"] if d["id"] == doc_id)


# ---------------------------------------------------------------- 정규화
@pytest.mark.parametrize("raw,want", [
    ("4,200,000", 4200000), ("3.700.000원", 3700000), ("0", 0), ("o", 0), ("금 1,250,000 원", 1250000),
    ("1,66,000", None),  # 자릿수 구분이 어긋나면 오독으로 보고 버린다
    ("", None), ("없음", None),
])
def test_parse_money(raw, want):
    assert parse_money(raw) == want


def test_parse_date_and_semester():
    assert parse_date("2026년 8월 21일") == "2026-08-21"
    assert parse_date("발급일 : 2026.09.01") == "2026-09-01"
    assert parse_date("2026년a 21일") is None
    assert parse_semester("2026학년도 제2학기") == "2026-2"
    assert parse_semester("2026-1") == "2026-1"
    assert parse_semester("2026학년도 여름 계절학기") is None


def test_rrn_is_masked_before_storage():
    assert mask_rrn("주민등록번호 900101-1234567") == "주민등록번호 900101-1******"


# ---------------------------------------------------------------- 추출
def _tuition_pdf(**over) -> bytes:
    from docgen import render

    data = {"variant": "A", "certificate_no": "2026-11111", "student_name": "김하늘", "birth": "2005-03-14",
            "school_name": "한빛대학교 대학원", "course": "석사과정", "department": "경영학과", "student_no": "202612345",
            "year": 2026, "term": 2, "grade": 1, "items": [("입학금", 200000), ("수업료", 4000000)], "tuition_total": 4200000,
            "scholarship_amount": 500000, "paid_amount": 3700000, "payment_date": "2026-08-21", "issue_date": "2026-09-01",
            "issuer": "한빛대학교 총장", "seal": True}
    data.update(over)
    return render("tuition_certificate", data, "pdf")


def test_extract_tuition_certificate_from_text_pdf():
    pages, boxes, engine = load_document(_tuition_pdf())
    assert engine == "pdf-text"
    r = extract(doc_spec("tuition", "tuition_payment_certificate"), boxes, pages[0].height)
    f = {k: v["value"] for k, v in r["fields"].items()}
    assert r["doc_check"]["matched"]
    assert f["student_name"] == "김하늘"
    assert f["school_name"] == "한빛대학교 대학원"      # 값 끝의 '대학원'을 라벨로 오인해 자르지 않는다
    assert f["semester"] == "2026-2"
    assert (f["tuition_total"], f["scholarship_amount"], f["paid_amount"]) == (4200000, 500000, 3700000)
    assert f["payment_date"] == "2026-08-21" and f["issue_date"] == "2026-09-01"
    assert f["certificate_no"] == "2026-11111" and f["seal"] == "있음"
    assert [(x["name"], x["amount"]) for x in r["tables"]["payment_items"]] == [("입학금", 200000), ("수업료", 4000000)]
    assert r["fields"]["paid_amount"]["bbox"] is not None       # 원본 옆에 표시할 위치


def test_variant_labels_and_missing_seal():
    pages, boxes, _ = load_document(_tuition_pdf(variant="B", seal=False))
    r = extract(doc_spec("tuition", "tuition_payment_certificate"), boxes, pages[0].height)
    assert r["fields"]["student_name"]["value"] == "김하늘"     # '학생성명' 라벨
    assert r["fields"]["paid_amount"]["value"] == 3700000       # '실납입금액' 라벨
    assert r["fields"]["seal"]["value"] == "없음"


def test_wrong_document_type_is_rejected():
    from docgen import render

    bill = render("tuition_bill", {"student_name": "김하늘", "student_no": "1", "school_name": "한빛대학교", "department": "경영학과",
                                   "year": 2026, "term": 2, "payment_date": "2026-08-21", "items": [("수업료", 1000)],
                                   "paid_amount": 1000, "issuer": "한빛대학교 총장"}, "pdf")
    pages, boxes, _ = load_document(bill)
    r = extract(doc_spec("tuition", "tuition_payment_certificate"), boxes, pages[0].height)
    assert not r["doc_check"]["matched"] and "고지서" in r["doc_check"]["reason"]


def test_family_certificate_table():
    from docgen import render

    fam = {"issue_date": "2026-09-01", "members": [
        {"relation": "본인", "name": "김민준", "birth": "1978-01-02", "rrn": "780102-1234567", "gender": "남"},
        {"relation": "배우자", "name": "이서연", "birth": "1980-05-06", "rrn": "800506-2******", "gender": "여"},
        {"relation": "자녀", "name": "김하늘", "birth": "2005-03-14", "rrn": "050314-4******", "gender": "여"}]}
    pages, boxes, _ = load_document(render("family_certificate", fam, "pdf"))
    r = extract(doc_spec("tuition", "family_relation_certificate"), boxes, pages[0].height)
    assert r["fields"]["principal_name"]["value"] == "김민준"
    assert r["fields"]["child_names"]["value"] == ["김하늘"]
    assert all("1234567" not in b.text for b in boxes)          # 주민번호 뒷자리는 추출 단계에서 가린다


# ---------------------------------------------------------------- 개인정보 마스킹
def test_masker_tokens_and_patterns():
    m = Masker()
    m.register_names(["김민준"])
    assert m.mask("김민준", "person_name") == "[이름A]"
    out = m.scrub("김민준 010-1234-5678 a@b.co 900101-1234567 계좌 123-456-789012 납입일 2026-08-21")
    assert "김민준" not in out and "1234-5678" not in out and "a@b.co" not in out and "1234567" not in out and "789012" not in out
    assert "2026-08-21" in out                                   # 날짜는 판정에 필요하므로 남긴다
    assert m.unmask("[이름A]님") == "김민준님"


# ---------------------------------------------------------------- 규정 조항 검색
def test_chunker_splits_by_clause():
    clauses = split_clauses(REGULATION["tuition"])
    ids = [c["clause_id"] for c in clauses]
    assert ids[:3] == ["제1조", "제2조", "제3조"] and len(ids) == 9
    assert clauses[2]["title"] == "지원 대상 교육기관"


def test_store_filters_by_version():
    async def run():
        s = RegulationStore()
        await s.index("tuition", "2026.1", "2026-01-01", REGULATION["tuition"])
        await s.index("tuition", "2027.1", "2027-01-01", REGULATION["tuition"].replace("500만원", "600만원"))
        old = await s.retrieve("tuition", "2026.1", "지원 한도", ["제5조"], 2)
        new = await s.retrieve("tuition", "2027.1", "지원 한도", ["제5조"], 2)
        assert "500만원" in old[0]["text"] and "600만원" in new[0]["text"]      # 당시 적용 버전의 조항만 나온다
        assert all(c["version"] == "2026.1" for c in old)
        assert old[0]["source"] == "지정 조항" and len(old) == 3
        assert await s.count("medical", "2026.1") == 0

    asyncio.run(run())


# ---------------------------------------------------------------- 결정 모델
def test_jev_request_and_response_mapping(monkeypatch):
    sent = {}

    def handler(request: httpx.Request) -> httpx.Response:
        sent["auth"] = request.headers["authorization"]
        sent["body"] = json.loads(request.content)
        return httpx.Response(200, json={"model": "jev-1.14.0", "usage": {"input_tokens": 10, "output_tokens": 2}, "answers": {
            "q1": {"type": "noul", "noul": 0.93},
            "q2": {"type": "choice", "choice": "a", "confidence": 0.8, "probabilities": {"a": 0.9, "b": 0.1}},
            "q3": {"type": "score", "score": 1.8, "confidence": 0.7, "legend": {"0": "x", "1": "y", "2": "z"}, "probabilities": {"0": 0.0, "1": 0.2, "2": 0.8}}}})

    real = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))
    p = JevProvider()
    p.key = "test-key"
    qs = [Question("q1", "noul", "예/아니오?", {"true": "예", "false": "아니오"}), Question("q2", "choice", "고르시오", {"a": "A", "b": "B"}),
          Question("q3", "score", "평가", ["x", "y", "z"])]
    answers, meta = asyncio.run(p.decide({"k": "v"}, qs))
    assert sent["auth"] == "Bearer test-key"
    assert sent["body"]["model"] == p.model and sent["body"]["state"] == {"k": "v"}
    assert sent["body"]["questions"]["q2"] == {"type": "choice", "instructions": "고르시오", "criteria": {"a": "A", "b": "B"}}
    assert answers["q1"].probabilities == {"true": 0.93, "false": pytest.approx(0.07)}
    assert answers["q1"].native_confidence is None              # noul에는 confidence 필드가 없다
    assert answers["q2"].choice == "a" and answers["q3"].score == 1.8
    assert meta["model"] == "jev-1.14.0"


def _judge(st: str, ev: dict, app: dict, ext: dict | None = None):
    async def run():
        service.get_store.cache_clear() if hasattr(service.get_store, "cache_clear") else None
        store = service.get_store()
        await store.index(st, POLICY[st]["version"], "2026-01-01", REGULATION[st])
        req = JudgeRequest(support_type=st, support_name=POLICY[st]["name"], policy_version=POLICY[st]["version"],
                           facts={"app": app, "ev": ev, "ext": ext or {}}, pii_names=["김민준"], judgments=POLICY[st]["judgments"],
                           field_types={"app.target_name": "person_name"})
        return await service.judge(req, LocalProvider())

    return {r["id"]: r for r in asyncio.run(run())["results"]}


def test_judge_two_phases_and_clauses():
    cert = {"school_name": "한빛대학교 대학원", "course": "석사과정", "payment_items": [{"name": "수업료", "amount": 1}],
            "issuer": "한빛대학교 총장", "issue_date": "2026-09-01", "seal": "있음", "certificate_no": "1", "_title": "등록금 납입증명서"}
    r = _judge("tuition", {"tuition_payment_certificate": cert}, {"relationship": "자녀"}, {"university": {"summary": "조회됨"}})
    assert r["J-INSTITUTION"]["answer"] == "graduate" and r["J-INSTITUTION"]["satisfied"] and r["J-INSTITUTION"]["phase"] == 1
    # 2차 요청: 1차 판정(대학원)을 입력으로 받아 '자녀의 대학원 과정'은 미충족
    assert r["J-ELIGIBLE-COURSE"]["phase"] == 2 and not r["J-ELIGIBLE-COURSE"]["satisfied"]
    assert r["J-ELIGIBLE-COURSE"]["inputs"]["과정_유형"] == "대학원"
    assert r["J-INSTITUTION"]["clauses"][0]["clause_id"] == "제3조"
    assert r["J-ITEMS"]["satisfied"] and r["J-FORMAL"]["satisfied"]
    assert 0.5 <= r["J-ITEMS"]["confidence"] <= 1.0


def test_judge_skips_when_required_input_unreadable():
    r = _judge("tuition", {"tuition_payment_certificate": {"school_name": None, "payment_items": None, "seal": "없음"}}, {"relationship": "본인"})
    assert r["J-INSTITUTION"]["status"] == "skipped" and r["J-ITEMS"]["status"] == "skipped"
    assert r["J-ELIGIBLE-COURSE"]["status"] == "skipped"        # 선행 판정이 없으면 판정하지 않는다
    assert r["J-FORMAL"]["status"] == "ok" and not r["J-FORMAL"]["satisfied"]


def test_medical_purpose_and_pii_not_in_question():
    receipt = {"hospital_name": "아름다운성형외과의원", "institution_kind": "의원", "department": "성형외과", "treatment": "쌍꺼풀 수술 (환자 김민준)"}
    r = _judge("medical", {"medical_receipt": receipt}, {"target_name": "김민준"})
    assert r["J-MED-PURPOSE"]["answer"] == "cosmetic" and not r["J-MED-PURPOSE"]["satisfied"]
    assert r["J-MED-INSTITUTION"]["satisfied"]
    assert "김민준" not in r["J-MED-PURPOSE"]["question"]        # 외부로 나가는 질문에는 성명이 없다


def test_local_model_is_deterministic_and_uncertain_on_unseen_text():
    q = Question("q", "choice", "", {"a": "", "b": ""}, {"local_text": "전혀 다른 문장", "examples": {"a": ["수업료"], "b": ["기숙사비"]}})
    p = LocalProvider()
    a1 = asyncio.run(p.decide({}, [q]))[0]["q"].probabilities
    a2 = asyncio.run(p.decide({}, [q]))[0]["q"].probabilities
    assert a1 == a2 and max(a1.values()) < 0.7


# ---------------------------------------------------------------- 보정
def test_calibration_bins_and_threshold():
    samples = [{"confidence": 0.99, "correct": True}] * 40 + [{"confidence": 0.65, "correct": False}] * 3 + [{"confidence": 0.62, "correct": True}] * 2
    r = calibration.calibrate(samples, 0.97, [0.5, 0.6, 0.7, 0.8, 0.9, 1.0], 5)
    low = next(b for b in r["bins"] if b["range"].startswith("0.60"))
    assert low["count"] == 5 and low["accuracy"] == 0.4
    assert r["recommended"]["met"] and 0.65 < r["recommended"]["threshold"] <= 0.99
    assert r["recommended"]["auto_accuracy"] == 1.0
