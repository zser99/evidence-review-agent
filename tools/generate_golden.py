"""정답 증빙 세트(golden set)와 데모용 가상 증빙을 만든다.

    python tools/generate_golden.py                 # seed/golden + seed/demo 전체 생성
    python tools/generate_golden.py --demo-only --today 2026-12-10

승인될 건과 반려될 건(금액 불일치, 학기 불일치, 서류 누락 등)을 섞어 만들고,
각 건에 정답(승인/반려), 반려 사유 코드, 조건별 정답, 추출 항목 정답을 붙인다.
"""
from __future__ import annotations

import argparse
import copy
import json
import random
import shutil
import zlib
from datetime import date, timedelta
from pathlib import Path

from docgen import render

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "seed" / "golden"
DEMO = ROOT / "seed" / "demo"

SURNAMES = ["김", "이", "박", "최", "정", "강", "조", "윤", "장", "임", "한", "오"]
GIVEN = ["민준", "서연", "도윤", "지우", "하준", "서현", "예준", "수아", "시우", "지민", "주원", "하은", "지호", "윤서",
         "건우", "채원", "현우", "다은", "준서", "소율", "태민", "가은", "성민", "유나"]
EMPLOYEES = [
    {"name": "김민준", "hire_date": "2019-03-04", "employee_no": "E2019031"},
    {"name": "이서연", "hire_date": "2015-07-01", "employee_no": "E2015112"},
    {"name": "박도윤", "hire_date": "2012-01-02", "employee_no": "E2012007"},
    {"name": "최지우", "hire_date": "2021-09-13", "employee_no": "E2021204"},
    {"name": "정하준", "hire_date": "2008-05-19", "employee_no": "E2008051"},
    {"name": "강수아", "hire_date": "2017-11-06", "employee_no": "E2017160"},
    {"name": "조현우", "hire_date": "2010-02-22", "employee_no": "E2010019"},
    {"name": "윤채원", "hire_date": "2023-04-03", "employee_no": "E2023044"},
]
UNDERGRAD = [("한빛대학교", "학사과정"), ("새솔대학교", "학사과정"), ("동해대학교", "학사과정"), ("늘푸른대학교", "학사과정"),
             ("가온대학교", "학사과정"), ("미래전문대학", "전문학사과정"), ("누리전문대학", "전문학사과정"),
             ("새솔사이버대학교", "학사과정"), ("한국방송통신대학교", "학사과정"), ("한울교육대학교", "학사과정")]
DEPARTMENTS = ["컴퓨터공학과", "경영학과", "간호학과", "전자공학과", "국어국문학과", "기계공학과", "사회복지학과", "건축학과", "화학공학과",
               "초등교육과", "시각디자인학과", "통계학과"]


def iso(d: date) -> str:
    return d.isoformat()


def child_of(emp_name: str, rng: random.Random) -> str:
    return emp_name[0] + rng.choice([g for g in GIVEN if g != emp_name[1:]])


def other_name(rng: random.Random, avoid: set[str]) -> str:
    while True:
        n = rng.choice(SURNAMES) + rng.choice(GIVEN)
        if n not in avoid:
            return n


# ---------------------------------------------------------------------------
# 학자금
# ---------------------------------------------------------------------------
def base_tuition(rng: random.Random, i: int, rel: str, school: tuple[str, str] | None = None, variant: str = "A",
                 scholarship: int | None = None, admission: bool = False, emp: dict | None = None) -> dict:
    emp = copy.deepcopy(emp or EMPLOYEES[i % len(EMPLOYEES)])
    if emp["hire_date"] > "2025-06-01":
        emp["hire_date"] = "2018-03-02"
    school_name, course = school or UNDERGRAD[i % len(UNDERGRAD)]
    student = emp["name"] if rel == "본인" else child_of(emp["name"], rng)
    base = 380000 if "방송통신" in school_name else rng.randrange(2600, 4700) * 1000
    items = [("수업료", base)]
    if admission:
        items.insert(0, ("입학금", rng.choice([150000, 200000, 300000])))
    total = sum(a for _, a in items)
    if scholarship is None:
        scholarship = rng.choice([0, 0, 500000, 1000000, 1200000]) if total > 2000000 else 0
    pay = date(2026, 8, 17) + timedelta(days=rng.randrange(0, 12))
    issue = pay + timedelta(days=rng.randrange(2, 8))
    applied = issue + timedelta(days=rng.randrange(1, 25))
    birth = date(1982, 1, 1) + timedelta(days=rng.randrange(0, 3000)) if rel == "본인" else date(2003, 1, 1) + timedelta(
        days=rng.randrange(0, 1700))
    cert = {
        "variant": variant, "certificate_no": f"2026-{rng.randrange(10000, 99999)}", "student_name": student,
        "birth": iso(birth), "school_name": school_name, "course": course, "department": rng.choice(DEPARTMENTS),
        "student_no": f"20{rng.randrange(20, 26)}{rng.randrange(10000, 99999)}", "year": 2026, "term": 2,
        "grade": rng.randrange(1, 5), "items": items, "tuition_total": total, "scholarship_amount": scholarship,
        "paid_amount": total - scholarship, "payment_date": iso(pay), "issue_date": iso(issue),
        "issuer": f"{school_name.split(' ')[0]} 총장", "seal": True,
    }
    ctx = {
        "employee": emp, "applied_on": iso(applied), "cert": cert, "cert_kind": "tuition_certificate", "family": None,
        "form": {"target_name": student, "relationship": rel, "school_name": school_name, "semester": "2026-2",
                 "requested_amount": total - scholarship},
        "jtruth": {"J-INSTITUTION": "undergraduate", "J-ITEMS": "all_eligible", "J-FORMAL": "pass",
                   "J-ELIGIBLE-COURSE": "true"},
    }
    if rel == "자녀":
        ctx["family"] = family_doc(rng, emp, [student], issue)
    return ctx


def family_doc(rng: random.Random, emp: dict, children: list[str], issue: date, spouse: str | None = None) -> dict:
    def rrn(b: date, male: bool) -> str:
        code = (3 if male else 4) if b.year >= 2000 else (1 if male else 2)
        return f"{b:%y%m%d}-{code}******"

    eb = date(1975, 1, 1) + timedelta(days=rng.randrange(0, 3600))
    members = [{"relation": "본인", "name": emp["name"], "birth": iso(eb), "rrn": rrn(eb, True), "gender": "남"}]
    sb = eb + timedelta(days=rng.randrange(-700, 900))
    members.append({"relation": "배우자", "name": spouse or other_name(rng, {emp["name"]}), "birth": iso(sb),
                    "rrn": rrn(sb, False), "gender": "여"})
    for ch in children:
        cb = date(2003, 1, 1) + timedelta(days=rng.randrange(0, 3000))
        male = rng.random() < 0.5
        members.append({"relation": "자녀", "name": ch, "birth": iso(cb), "rrn": rrn(cb, male),
                        "gender": "남" if male else "여"})
    return {"members": members, "issue_date": iso(issue)}


def tuition_truth(cert: dict) -> dict:
    t = {
        "student_name": cert["student_name"], "school_name": cert["school_name"], "course": cert["course"],
        "department": cert["department"], "semester": f"{cert['year']}-{cert['term']}",
        "tuition_total": cert["tuition_total"], "paid_amount": cert["paid_amount"],
        "payment_date": cert["payment_date"], "certificate_no": cert["certificate_no"],
        "seal": "있음" if cert.get("seal", True) else "없음",
        "payment_items": [{"name": n, "amount": a} for n, a in cert["items"]],
    }
    if cert.get("show_scholarship", True):
        t["scholarship_amount"] = cert["scholarship_amount"]
    if cert.get("issue_date"):
        t["issue_date"] = cert["issue_date"]
    if cert.get("issuer"):
        t["issuer"] = cert["issuer"]
    return t


def family_truth(fam: dict, list_key: str, relations: tuple[str, ...]) -> dict:
    return {
        "principal_name": next(m["name"] for m in fam["members"] if m["relation"] == "본인"),
        list_key: [m["name"] for m in fam["members"] if m["relation"] in relations],
    }


def tuition_cases(rng: random.Random) -> list[dict]:
    out: list[dict] = []
    n = [0]

    def add(title, scenario, expected, reasons, ctx, fmt="png", quality=None, mut=None):
        n[0] += 1
        if mut:
            mut(ctx)
        out.append({"id": f"T{n[0]:02d}", "support_type": "tuition", "scenario": scenario, "title": title,
                    "expected": expected, "reasons": list(reasons), "ctx": ctx, "fmt": fmt, "quality": quality})

    def b(rel="본인", **kw):
        return base_tuition(rng, n[0], rel, **kw)

    # ---- 승인 ----
    add("정상 승인 · 본인 · 표 서식", "approve", "APPROVE", [], b())
    add("정상 승인 · 자녀 · 가족관계증명서 포함", "approve", "APPROVE", [], b("자녀"))
    add("정상 승인 · 본인 · 세로형 서식", "approve", "APPROVE", [], b(variant="B"))
    add("정상 승인 · 자녀 · 장학금 차감(JPG)", "approve", "APPROVE", [], b("자녀", variant="B", scholarship=1200000), fmt="jpg")
    add("정상 승인 · 본인 · 전문대학(PDF)", "approve", "APPROVE", [], b(school=("미래전문대학", "전문학사과정")), fmt="pdf")
    add("정상 승인 · 자녀 · 입학금+수업료(PDF)", "approve", "APPROVE", [], b("자녀", admission=True, scholarship=0), fmt="pdf")
    add("정상 승인 · 본인 · 사이버대학", "approve", "APPROVE", [], b(school=("새솔사이버대학교", "학사과정"), variant="B"))
    add("정상 승인 · 자녀 · 방송통신대학", "approve", "APPROVE", [], b("자녀", school=("한국방송통신대학교", "학사과정")))

    def grad(ctx, course="석사과정"):
        ctx["cert"]["school_name"] += " 대학원"
        ctx["cert"]["course"] = course
        ctx["form"]["school_name"] = ctx["cert"]["school_name"]
        ctx["jtruth"]["J-INSTITUTION"] = "graduate"

    add("정상 승인 · 본인 · 대학원 석사", "approve_grad_self", "APPROVE", [], b(school=("한빛대학교", "학사과정")), mut=grad)
    add("정상 승인 · 본인 · 대학원 박사(PDF)", "approve_grad_self", "APPROVE", [], b(school=("새솔대학교", "학사과정"), variant="B"),
        fmt="pdf", mut=lambda c: grad(c, "박사과정"))
    add("정상 승인 · 자녀 · 교육대학(스캔 PDF)", "approve", "APPROVE", [], b("자녀", school=("한울교육대학교", "학사과정")), fmt="scanpdf")
    add("정상 승인 · 자녀 · 전문대학 장학금", "approve", "APPROVE", [], b("자녀", school=("누리전문대학", "전문학사과정"), variant="B", scholarship=500000))
    add("정상 승인 · 본인 · 약한 스캔 잡음", "approve_scan", "APPROVE", [], b(), fmt="jpg", quality="light")
    add("정상 승인 · 자녀 · 약한 스캔 잡음", "approve_scan", "APPROVE", [], b("자녀"), fmt="jpg", quality="light")

    def at_limit(ctx):
        c = ctx["cert"]
        c["items"] = [("수업료", 5000000)]
        c["tuition_total"], c["scholarship_amount"], c["paid_amount"] = 5000000, 0, 5000000
        ctx["form"]["requested_amount"] = 5000000

    add("정상 승인 · 한도와 같은 금액(500만원)", "approve_limit", "APPROVE", [], b("자녀", variant="B"), mut=at_limit)
    add("정상 승인 · 본인 · 장학금 차감", "approve", "APPROVE", [], b(scholarship=1000000))
    add("정상(심한 저화질 스캔) · 자녀", "approve_poor_scan", "APPROVE", [], b("자녀"), fmt="jpg", quality="heavy")
    add("정상(심한 저화질 스캔) · 본인", "approve_poor_scan", "APPROVE", [], b(variant="B"), fmt="jpg", quality="heavy")
    add("정상 승인 · 자녀 · 전문대학 입학금", "approve", "APPROVE", [], b("자녀", school=("미래전문대학", "전문학사과정"), admission=True))
    add("정상 승인 · 본인 · 세로형(PDF)", "approve", "APPROVE", [], b(variant="B"), fmt="pdf")

    # ---- 반려: 금액 불일치 ----
    def amt(delta):
        def f(ctx):
            ctx["form"]["requested_amount"] += delta
        return f

    def amt_gross(ctx):
        ctx["form"]["requested_amount"] = ctx["cert"]["tuition_total"]

    add("금액 불일치 · 신청 금액이 실납입액보다 큼", "amount_mismatch", "REJECT", ["C-AMOUNT"], b(scholarship=0), mut=amt(500000))
    add("금액 불일치 · 장학금 미차감 금액으로 신청", "amount_mismatch", "REJECT", ["C-AMOUNT"], b("자녀", scholarship=1000000), mut=amt_gross)
    add("금액 불일치 · 자릿수 오기(PDF)", "amount_mismatch", "REJECT", ["C-AMOUNT"], b(variant="B", scholarship=0), fmt="pdf", mut=amt(-30000))
    add("금액 불일치 · 장학금 미차감(JPG)", "amount_mismatch", "REJECT", ["C-AMOUNT"], b("자녀", variant="B", scholarship=500000), fmt="jpg", mut=amt_gross)
    add("금액 불일치 · 약한 스캔 잡음", "amount_mismatch", "REJECT", ["C-AMOUNT"], b(scholarship=0), fmt="jpg", quality="light", mut=amt(1000000))

    # ---- 반려: 학기 불일치 ----
    def sem_form(ctx):
        ctx["form"]["semester"] = "2026-1"

    def sem_cert(ctx):
        ctx["cert"]["term"] = 1
        ctx["cert"]["payment_date"] = "2026-08-20"

    add("학기 불일치 · 신청 2026-1 / 증빙 2026-2", "semester_mismatch", "REJECT", ["C-SEMESTER"], b(), mut=sem_form)
    add("학기 불일치 · 자녀 · 세로형", "semester_mismatch", "REJECT", ["C-SEMESTER"], b("자녀", variant="B"), mut=sem_form)
    add("학기 불일치 · 증빙이 1학기(PDF)", "semester_mismatch", "REJECT", ["C-SEMESTER"], b(), fmt="pdf", mut=sem_cert)

    # ---- 반려: 서류 누락 ----
    def no_family(ctx):
        ctx["family"] = None

    add("서류 누락 · 자녀인데 가족관계증명서 없음", "missing_document", "REJECT", ["MISSING:family_relation_certificate"], b("자녀"), mut=no_family)
    add("서류 누락 · 가족관계증명서 없음(PDF)", "missing_document", "REJECT", ["MISSING:family_relation_certificate"], b("자녀", variant="B"), fmt="pdf", mut=no_family)
    add("서류 누락 · 가족관계증명서 없음(JPG)", "missing_document", "REJECT", ["MISSING:family_relation_certificate"], b("자녀"), fmt="jpg", mut=no_family)

    # ---- 반려: 성명 불일치 ----
    def name_mis(ctx):
        avoid = {ctx["cert"]["student_name"], ctx["employee"]["name"]}
        ctx["cert"]["student_name"] = other_name(rng, avoid)

    add("성명 불일치 · 다른 학생의 증명서", "name_mismatch", "REJECT", ["C-NAME"], b(), mut=name_mis)

    def name_mis_child(ctx):
        sib = child_of(ctx["employee"]["name"], rng)
        while sib == ctx["form"]["target_name"]:
            sib = child_of(ctx["employee"]["name"], rng)
        ctx["cert"]["student_name"] = sib
        ctx["family"]["members"].append({"relation": "자녀", "name": sib, "birth": "2006-05-17", "rrn": "060517-4******", "gender": "여"})

    add("성명 불일치 · 다른 자녀의 증명서", "name_mismatch", "REJECT", ["C-NAME"], b("자녀"), mut=name_mis_child)

    # ---- 반려: 한도 초과 ----
    def over(v):
        def f(ctx):
            c = ctx["cert"]
            c["items"] = [("수업료", v)]
            c["tuition_total"], c["scholarship_amount"], c["paid_amount"] = v, 0, v
            ctx["form"]["requested_amount"] = v
        return f

    add("한도 초과 · 580만원", "over_limit", "REJECT", ["S-LIMIT"], b(), mut=over(5800000))
    add("한도 초과 · 자녀 · 642만원(PDF)", "over_limit", "REJECT", ["S-LIMIT"], b("자녀", variant="B"), fmt="pdf", mut=over(6420000))

    # ---- 반려: 신청 기한 경과 ----
    def late(days):
        def f(ctx):
            ctx["applied_on"] = iso(date.fromisoformat(ctx["cert"]["payment_date"]) + timedelta(days=days))
        return f

    add("기한 경과 · 납입 후 75일", "deadline", "REJECT", ["S-DEADLINE"], b(), mut=late(75))
    add("기한 경과 · 자녀 · 납입 후 61일", "deadline", "REJECT", ["S-DEADLINE"], b("자녀", variant="B"), mut=late(61))

    # ---- 반려: 비대상 교육기관 ----
    def inst(name, course, issuer, kind):
        def f(ctx):
            c = ctx["cert"]
            c["school_name"], c["course"], c["issuer"] = name, course, issuer
            c["items"] = [("수업료", 1260000)]
            c["tuition_total"], c["scholarship_amount"], c["paid_amount"] = 1260000, 0, 1260000
            ctx["form"]["school_name"] = name
            ctx["form"]["requested_amount"] = 1260000
            ctx["jtruth"]["J-INSTITUTION"] = kind
            ctx["jtruth"]["J-ELIGIBLE-COURSE"] = "false"
        return f

    add("비대상 기관 · 대학 부설 평생교육원(학점은행제)", "ineligible_institution", "REJECT", ["J-INSTITUTION"], b(),
        mut=inst("한빛대학교 평생교육원", "학점은행제과정", "한빛대학교 평생교육원 원장", "lifelong"))
    add("비대상 기관 · 원격평생교육원", "ineligible_institution", "REJECT", ["J-INSTITUTION"], b("자녀", variant="B"),
        mut=inst("미래원격평생교육원", "학점은행제과정", "미래원격평생교육원 원장", "lifelong"))
    add("비대상 기관 · 사설 어학원", "ineligible_institution", "REJECT", ["J-INSTITUTION"], b(),
        mut=inst("에이스어학원", "토익정규과정", "에이스어학원 원장", "private"))
    add("비대상 기관 · IT 아카데미(PDF)", "ineligible_institution", "REJECT", ["J-INSTITUTION"], b(variant="B"), fmt="pdf",
        mut=inst("코드마스터아카데미", "개발자양성과정", "코드마스터아카데미 원장", "private"))

    # ---- 반려: 비대상 항목 ----
    def extra_item(name, amount, replace=False):
        def f(ctx):
            c = ctx["cert"]
            c["items"] = [(name, amount)] if replace else c["items"] + [(name, amount)]
            c["tuition_total"] = sum(a for _, a in c["items"])
            c["paid_amount"] = c["tuition_total"] - c["scholarship_amount"]
            ctx["form"]["requested_amount"] = c["paid_amount"]
            ctx["jtruth"]["J-ITEMS"] = "includes_ineligible"
        return f

    add("비대상 항목 · 기숙사비 포함", "ineligible_items", "REJECT", ["J-ITEMS"], b(scholarship=0), mut=extra_item("기숙사비", 850000))
    add("비대상 항목 · 학생회비 포함", "ineligible_items", "REJECT", ["J-ITEMS"], b("자녀", variant="B", scholarship=0), mut=extra_item("학생회비", 20000))
    add("비대상 항목 · 계절학기 수업료", "ineligible_items", "REJECT", ["J-ITEMS"], b(scholarship=0), mut=extra_item("계절학기 수업료", 420000, replace=True))
    add("비대상 항목 · 교재비·동문회비 포함(PDF)", "ineligible_items", "REJECT", ["J-ITEMS"], b("자녀", scholarship=0), fmt="pdf",
        mut=lambda c: (extra_item("교재비", 120000)(c), extra_item("동문회비", 30000)(c)))

    # ---- 반려: 정식 증빙 요건 미비 ----
    def no_seal(ctx):
        ctx["cert"]["seal"] = False
        ctx["jtruth"]["J-FORMAL"] = "fail"

    def no_seal_date(ctx):
        ctx["cert"]["seal"] = False
        ctx["cert"]["issue_date"] = None
        ctx["jtruth"]["J-FORMAL"] = "fail"

    add("증빙 요건 미비 · 직인 없음", "informal_document", "REJECT", ["J-FORMAL"], b(), mut=no_seal)
    add("증빙 요건 미비 · 직인·발급일 없음", "informal_document", "REJECT", ["J-FORMAL"], b("자녀", variant="B"), mut=no_seal_date)

    # ---- 반려: 자녀의 대학원 과정 ----
    def grad_child(ctx):
        grad(ctx)
        ctx["jtruth"]["J-ELIGIBLE-COURSE"] = "false"

    add("자녀의 대학원 과정 · 석사", "grad_child", "REJECT", ["J-ELIGIBLE-COURSE"], b("자녀", school=("한빛대학교", "학사과정")), mut=grad_child)
    add("자녀의 대학원 과정 · 석사(PDF)", "grad_child", "REJECT", ["J-ELIGIBLE-COURSE"], b("자녀", school=("동해대학교", "학사과정"), variant="B"), fmt="pdf", mut=grad_child)

    # ---- 반려: 기타 ----
    def short_tenure(ctx):
        ctx["employee"]["hire_date"] = "2026-03-02"

    add("재직기간 1년 미만", "tenure", "REJECT", ["S-TENURE"], b(), mut=short_tenure)

    def fam_no_child(ctx):
        for m in ctx["family"]["members"]:
            if m["relation"] == "자녀":
                m["name"] = other_name(rng, {ctx["form"]["target_name"]})

    add("가족관계증명서에 대상자 없음", "family_mismatch", "REJECT", ["C-FAMILY-CHILD"], b("자녀"), mut=fam_no_child)

    def bill(ctx):
        ctx["cert_kind"] = "tuition_bill"
        ctx["jtruth"] = {}

    add("잘못된 서류 · 납입증명서 대신 고지서 제출", "wrong_document", "REJECT", ["MISSING:tuition_payment_certificate"], b(), mut=bill)

    def school_mis(ctx):
        ctx["form"]["school_name"] = "가온대학교" if ctx["cert"]["school_name"] != "가온대학교" else "동해대학교"

    add("학교명 불일치", "school_mismatch", "REJECT", ["C-SCHOOL"], b(), mut=school_mis)

    def fam_other(ctx):
        ctx["family"]["members"][0]["name"] = other_name(rng, {ctx["employee"]["name"]})

    add("가족관계증명서가 다른 사람 기준", "family_mismatch", "REJECT", ["C-FAMILY-SELF"], b("자녀", variant="B"), mut=fam_other)
    return out


# ---------------------------------------------------------------------------
# 의료비
# ---------------------------------------------------------------------------
HOSPITALS = {
    "내과": ("한빛내과의원", "의원"), "정형외과": ("새솔정형외과의원", "의원"), "치과": ("미래치과의원", "치과의원"),
    "이비인후과": ("바른이비인후과의원", "의원"), "소아청소년과": ("튼튼소아청소년과의원", "의원"), "안과": ("밝은안과의원", "의원"),
    "한방내과": ("늘푸른한의원", "한의원"), "산부인과": ("가온대학교병원", "상급종합병원"), "피부과": ("연세피부과의원", "의원"),
    "성형외과": ("아름다운성형외과의원", "의원"), "가정의학과": ("동해병원", "병원"), "외과": ("서울중앙종합병원", "종합병원"),
}


def base_medical(rng: random.Random, i: int, rel: str, dept: str, treatment: str, paid: int | None = None,
                 non_covered_ratio: float = 0.1) -> dict:
    emp = copy.deepcopy(EMPLOYEES[(i * 3 + 1) % len(EMPLOYEES)])
    if rel == "본인":
        patient = emp["name"]
    elif rel == "자녀":
        patient = child_of(emp["name"], rng)
    else:
        patient = other_name(rng, {emp["name"]})
    hospital, kind = HOSPITALS[dept]
    paid = paid or rng.randrange(18, 420) * 1000
    non = int(paid * non_covered_ratio / 100) * 100
    own = paid - non
    ins = int(own * rng.uniform(1.6, 2.6) / 100) * 100
    names = ["진찰료", "검사료", "처치 및 수술료", "투약 및 조제료"]
    parts_own = split(rng, own, 4)
    parts_ins = split(rng, ins, 4)
    parts_non = [0, non // 2 // 100 * 100, non - non // 2 // 100 * 100, 0]
    lines = [(names[k], parts_own[k], parts_ins[k], parts_non[k]) for k in range(4)]
    rdate = date(2026, 8, 24) + timedelta(days=rng.randrange(0, 40))
    applied = rdate + timedelta(days=rng.randrange(1, 45))
    receipt = {
        "patient_no": f"{rng.randrange(10000000, 99999999)}", "patient_name": patient,
        "treatment_period": f"{rdate:%Y.%m.%d} ~ {rdate:%Y.%m.%d}", "department": dept, "treatment": treatment,
        "receipt_no": f"{rdate:%Y%m%d}-{rng.randrange(1000, 9999)}", "lines": lines, "total": own + ins + non,
        "insurer_total": ins, "patient_total": paid, "paid_amount": paid, "receipt_date": iso(rdate),
        "institution_kind": kind, "business_no": f"{rng.randrange(100, 999)}-{rng.randrange(10, 99)}-{rng.randrange(10000, 99999)}",
        "hospital_name": hospital, "representative": other_name(rng, set()),
    }
    ctx = {
        "employee": emp, "applied_on": iso(applied), "cert": receipt, "cert_kind": "medical_receipt", "family": None,
        "form": {"target_name": patient, "relationship": rel, "hospital_name": hospital, "receipt_date": iso(rdate),
                 "requested_amount": paid},
        "jtruth": {"J-MED-INSTITUTION": "true", "J-MED-PURPOSE": "treatment"},
    }
    if rel != "본인":
        children = [patient] if rel == "자녀" else [child_of(emp["name"], rng)]
        ctx["family"] = family_doc(rng, emp, children, rdate, spouse=patient if rel == "배우자" else None)
    return ctx


def split(rng: random.Random, total: int, k: int) -> list[int]:
    if total <= 0:
        return [0] * k
    cuts = sorted(rng.randrange(0, total // 100 + 1) * 100 for _ in range(k - 1))
    parts = [cuts[0]] + [cuts[j] - cuts[j - 1] for j in range(1, k - 1)] + [total - cuts[-1]]
    return parts


def medical_truth(r: dict) -> dict:
    return {k: v for k, v in _medical_truth(r).items() if v not in ("", None)}


def _medical_truth(r: dict) -> dict:
    return {
        "patient_name": r["patient_name"], "hospital_name": r["hospital_name"], "institution_kind": r["institution_kind"],
        "department": r["department"], "treatment": r["treatment"], "patient_total": r["patient_total"],
        "paid_amount": r["paid_amount"], "receipt_date": r["receipt_date"], "receipt_no": r["receipt_no"],
        "business_no": r["business_no"],
    }


def medical_cases(rng: random.Random) -> list[dict]:
    out: list[dict] = []
    n = [0]

    def add(title, scenario, expected, reasons, ctx, fmt="png", quality=None, mut=None):
        n[0] += 1
        if mut:
            mut(ctx)
        out.append({"id": f"M{n[0]:02d}", "support_type": "medical", "scenario": scenario, "title": title,
                    "expected": expected, "reasons": list(reasons), "ctx": ctx, "fmt": fmt, "quality": quality})

    def b(rel, dept, treatment, **kw):
        return base_medical(rng, n[0], rel, dept, treatment, **kw)

    add("정상 승인 · 본인 · 내과 편도염", "approve", "APPROVE", [], b("본인", "내과", "급성 편도염 진료"))
    add("정상 승인 · 자녀 · 소아청소년과 폐렴", "approve", "APPROVE", [], b("자녀", "소아청소년과", "폐렴 입원 치료", paid=386000))
    add("정상 승인 · 배우자 · 정형외과 골절(PDF)", "approve", "APPROVE", [], b("배우자", "정형외과", "발목 골절 수술", paid=1240000), fmt="pdf")
    add("정상 승인 · 본인 · 치과 충치", "approve", "APPROVE", [], b("본인", "치과", "충치 치료", non_covered_ratio=0.4))
    add("정상 승인 · 본인 · 이비인후과(JPG)", "approve", "APPROVE", [], b("본인", "이비인후과", "급성 중이염 치료"), fmt="jpg")
    add("정상 승인 · 자녀 · 안과 결막염", "approve", "APPROVE", [], b("자녀", "안과", "결막염 진료"))
    add("정상 승인 · 본인 · 한의원 염좌", "approve", "APPROVE", [], b("본인", "한방내과", "발목 염좌 침 치료"))
    add("정상 승인 · 배우자 · 대학병원 수술", "approve", "APPROVE", [], b("배우자", "산부인과", "자궁근종 수술", paid=1850000))
    add("정상 승인 · 본인 · 피부과 아토피(스캔 잡음)", "approve_scan", "APPROVE", [], b("본인", "피부과", "아토피 피부염 치료"), fmt="jpg", quality="light")
    add("정상 승인 · 본인 · 내과 위염(스캔 PDF)", "approve", "APPROVE", [], b("본인", "내과", "위염 진료 및 투약"), fmt="scanpdf")
    add("정상 승인 · 본인 · 외과 맹장염 수술", "approve", "APPROVE", [], b("본인", "외과", "급성 충수염 수술", paid=960000))
    add("정상(판단 어려움) · 정형외과 도수치료", "approve_ambiguous", "APPROVE", [], b("본인", "정형외과", "허리 통증 도수치료", non_covered_ratio=0.8))

    def amt(delta):
        def f(ctx):
            ctx["form"]["requested_amount"] += delta
        return f

    add("금액 불일치 · 신청 금액이 더 큼", "amount_mismatch", "REJECT", ["C-AMOUNT"], b("본인", "내과", "독감 진료"), mut=amt(50000))
    add("금액 불일치 · 진료비 총액으로 신청(PDF)", "amount_mismatch", "REJECT", ["C-AMOUNT"], b("자녀", "이비인후과", "편도 절제 수술", paid=540000), fmt="pdf",
        mut=lambda c: c["form"].__setitem__("requested_amount", c["cert"]["total"]))

    def purpose(kind):
        def f(ctx):
            ctx["jtruth"]["J-MED-PURPOSE"] = kind
        return f

    add("미용·성형 · 쌍꺼풀 수술", "non_treatment", "REJECT", ["J-MED-PURPOSE"], b("본인", "성형외과", "쌍꺼풀 수술", paid=1500000, non_covered_ratio=1.0), mut=purpose("cosmetic"))
    add("미용·성형 · 보톡스 시술", "non_treatment", "REJECT", ["J-MED-PURPOSE"], b("배우자", "피부과", "보톡스 시술", paid=220000, non_covered_ratio=1.0), mut=purpose("cosmetic"))
    add("미용·성형 · 치아 미백", "non_treatment", "REJECT", ["J-MED-PURPOSE"], b("본인", "치과", "치아 미백", paid=300000, non_covered_ratio=1.0), mut=purpose("cosmetic"))
    add("검진·예방 · 종합건강검진", "non_treatment", "REJECT", ["J-MED-PURPOSE"], b("본인", "가정의학과", "종합건강검진", paid=450000, non_covered_ratio=1.0), mut=purpose("preventive"))
    add("검진·예방 · 독감 예방접종", "non_treatment", "REJECT", ["J-MED-PURPOSE"], b("자녀", "소아청소년과", "독감 예방접종", paid=40000, non_covered_ratio=1.0), mut=purpose("preventive"))
    add("건강증진 · 영양주사", "non_treatment", "REJECT", ["J-MED-PURPOSE"], b("본인", "가정의학과", "영양주사", paid=80000, non_covered_ratio=1.0), mut=purpose("wellness"))

    def late(ctx):
        ctx["applied_on"] = iso(date.fromisoformat(ctx["cert"]["receipt_date"]) + timedelta(days=97))

    add("기한 경과 · 수납 후 97일", "deadline", "REJECT", ["S-DEADLINE"], b("본인", "내과", "위염 진료 및 투약"), mut=late)

    def no_family(ctx):
        ctx["family"] = None

    add("서류 누락 · 자녀인데 가족관계증명서 없음", "missing_document", "REJECT", ["MISSING:family_relation_certificate"], b("자녀", "소아청소년과", "장염 진료"), mut=no_family)

    def name_mis(ctx):
        ctx["cert"]["patient_name"] = other_name(rng, {ctx["form"]["target_name"]})

    add("환자 성명 불일치", "name_mismatch", "REJECT", ["C-PATIENT"], b("본인", "정형외과", "손목 염좌 치료"), mut=name_mis)

    def non_medical(ctx):
        r = ctx["cert"]
        r["hospital_name"], r["institution_kind"], r["department"], r["treatment"] = "뷰티라인피부관리실", "", "피부관리", "피부 관리 10회권"
        ctx["form"]["hospital_name"] = r["hospital_name"]
        ctx["jtruth"] = {"J-MED-INSTITUTION": "false", "J-MED-PURPOSE": "cosmetic"}

    add("비의료기관 · 피부관리실", "non_medical_institution", "REJECT", ["J-MED-INSTITUTION"], b("본인", "피부과", "피부 관리", paid=330000, non_covered_ratio=1.0), mut=non_medical)
    add("한도 초과 · 240만원", "over_limit", "REJECT", ["S-LIMIT"], b("본인", "외과", "담낭 절제 수술", paid=2400000))

    def slip(ctx):
        ctx["cert_kind"] = "card_slip"
        ctx["jtruth"] = {}

    add("잘못된 서류 · 카드 매출전표 제출", "wrong_document", "REJECT", ["MISSING:medical_receipt"], b("본인", "내과", "감기 진료"), mut=slip)

    def date_mis(ctx):
        ctx["form"]["receipt_date"] = iso(date.fromisoformat(ctx["cert"]["receipt_date"]) + timedelta(days=3))

    add("수납일 불일치", "date_mismatch", "REJECT", ["C-DATE"], b("본인", "안과", "안구건조증 진료"), mut=date_mis)
    add("최소 금액 미만 · 8,000원", "under_minimum", "REJECT", ["S-MIN"], b("본인", "내과", "감기 진료", paid=8000))
    return out


# ---------------------------------------------------------------------------
# 출력
# ---------------------------------------------------------------------------
EXT = {"png": "png", "jpg": "jpg", "pdf": "pdf", "scanpdf": "pdf"}


def materialize(case: dict, files_dir: Path, rel_prefix: str) -> dict:
    ctx = case["ctx"]
    st = case["support_type"]
    docs = []
    kind = ctx["cert_kind"]
    main_doc_id = "tuition_payment_certificate" if st == "tuition" else "medical_receipt"
    fname = f"{case['id']}_{main_doc_id}.{EXT[case['fmt']]}"
    (files_dir / fname).write_bytes(render(kind, ctx["cert"], case["fmt"], case["quality"], seed=zlib.crc32(case["id"].encode()) % 9973))
    if kind in ("tuition_certificate", "medical_receipt"):
        truth = tuition_truth(ctx["cert"]) if st == "tuition" else medical_truth(ctx["cert"])
    else:
        truth = None  # 인정되지 않는 서류: 추출 정답 없음
    docs.append({"doc_id": main_doc_id, "file": f"{rel_prefix}/{fname}", "truth": truth, "actual_kind": kind})
    if ctx["family"]:
        ffmt = "png" if case["fmt"] in ("png", "jpg") else "pdf"
        fname = f"{case['id']}_family_relation_certificate.{ffmt}"
        (files_dir / fname).write_bytes(render("family_certificate", ctx["family"], ffmt, None))
        list_key, rels = ("child_names", ("자녀",)) if st == "tuition" else ("family_names", ("배우자", "자녀"))
        docs.append({"doc_id": "family_relation_certificate", "file": f"{rel_prefix}/{fname}",
                     "truth": family_truth(ctx["family"], list_key, rels), "actual_kind": "family_certificate"})
    return {
        "id": case["id"], "support_type": st, "scenario": case["scenario"], "title": case["title"],
        "employee": ctx["employee"], "applied_on": ctx["applied_on"], "form": ctx["form"], "documents": docs,
        "format": case["fmt"], "quality": case["quality"] or "clean",
        "expected": {"verdict": case["expected"], "reasons": case["reasons"], "judgments": ctx["jtruth"]},
    }


def build_golden():
    rng = random.Random(20261115)
    if GOLDEN.exists():
        shutil.rmtree(GOLDEN)
    files = GOLDEN / "files"
    files.mkdir(parents=True)
    cases = [materialize(c, files, "files") for c in tuition_cases(rng) + medical_cases(rng)]
    manifest = {
        "name": "가상 증빙 정답 세트", "version": "1.0", "generated_with": "tools/generate_golden.py (seed 20261115)",
        "description": "승인·반려 정답과 사유를 붙인 가상 학자금·의료비 증빙 세트. 실제 개인정보를 포함하지 않는다.",
        "cases": cases,
    }
    (GOLDEN / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    appr = sum(1 for c in cases if c["expected"]["verdict"] == "APPROVE")
    print(f"golden: {len(cases)} cases (approve {appr}, reject {len(cases) - appr}), "
          f"{sum(len(c['documents']) for c in cases)} files -> {GOLDEN}")


def build_demo(today: date):
    """발표 데모용 증빙: 김민준(구성원) 계정으로 로그인해 순서대로 올린다."""
    rng = random.Random(7)
    if DEMO.exists():
        shutil.rmtree(DEMO)
    DEMO.mkdir(parents=True)
    emp = EMPLOYEES[0]
    pay = today - timedelta(days=9)
    issue = today - timedelta(days=3)

    def cert(scholarship: int) -> dict:
        return {"variant": "A", "certificate_no": "2026-48213" if scholarship else "2026-48377", "student_name": emp["name"],
                "birth": "1990-04-12", "school_name": "한빛대학교 대학원", "course": "석사과정", "department": "경영학과",
                "student_no": "202612345", "year": 2026, "term": 2, "grade": 1, "items": [("수업료", 4200000)],
                "tuition_total": 4200000, "scholarship_amount": scholarship, "paid_amount": 4200000 - scholarship,
                "payment_date": iso(pay), "issue_date": iso(issue), "issuer": "한빛대학교 총장", "seal": True}

    child = "김하은"
    fam = family_doc(rng, emp, [child], issue)
    child_cert = {"variant": "B", "certificate_no": "2026-51902", "student_name": child, "birth": "2006-02-11",
                  "school_name": "새솔대학교", "course": "학사과정", "department": "간호학과", "student_no": "202501877",
                  "year": 2026, "term": 2, "grade": 2, "items": [("수업료", 3650000), ("기숙사비", 900000)],
                  "tuition_total": 4550000, "scholarship_amount": 0, "paid_amount": 4550000, "payment_date": iso(pay),
                  "issue_date": iso(issue), "issuer": "새솔대학교 총장", "seal": True}
    rd = today - timedelta(days=5)
    ok_receipt = base_medical(random.Random(11), 0, "본인", "내과", "급성 편도염 진료", paid=47300)["cert"]
    ok_receipt.update({"patient_name": emp["name"], "receipt_date": iso(rd), "treatment_period": f"{rd:%Y.%m.%d} ~ {rd:%Y.%m.%d}",
                       "receipt_no": f"{rd:%Y%m%d}-0412"})
    cos_receipt = base_medical(random.Random(12), 0, "본인", "성형외과", "쌍꺼풀 수술", paid=1500000, non_covered_ratio=1.0)["cert"]
    cos_receipt.update({"patient_name": emp["name"], "receipt_date": iso(rd), "treatment_period": f"{rd:%Y.%m.%d} ~ {rd:%Y.%m.%d}",
                        "receipt_no": f"{rd:%Y%m%d}-0077"})
    files = [
        ("01_학자금_납입증명서_금액불일치.png", "tuition_certificate", cert(500000), "png"),
        ("02_학자금_납입증명서_정상.png", "tuition_certificate", cert(0), "png"),
        ("03_의료비_진료비영수증_정상.png", "medical_receipt", ok_receipt, "png"),
        ("04_의료비_진료비영수증_성형.png", "medical_receipt", cos_receipt, "png"),
        ("05_학자금_자녀_납입증명서_기숙사비포함.pdf", "tuition_certificate", child_cert, "pdf"),
        ("06_가족관계증명서.png", "family_certificate", fam, "png"),
    ]
    for name, kind, data, fmt in files:
        (DEMO / name).write_bytes(render(kind, data, fmt))
    guide = {
        "generated_for": iso(today),
        "login": {"username": "employee1", "password": "demo1234", "name": emp["name"]},
        "steps": [
            {"step": 1, "title": "학자금 · 금액 불일치 → 보완 안내와 함께 반려", "support_type": "tuition", "file": files[0][0],
             "form": {"target_name": emp["name"], "relationship": "본인", "school_name": "한빛대학교 대학원", "semester": "2026-2", "requested_amount": 4200000},
             "expect": "증빙의 실납입액 3,700,000원 ≠ 신청 금액 4,200,000원 → 반려 + 보완 안내"},
            {"step": 2, "title": "학자금 · 제대로 된 납입증명서로 교체 → 높은 확신도로 승인", "support_type": "tuition", "file": files[1][0],
             "form": "1번과 같은 신청서에서 증빙만 교체 후 다시 제출", "expect": "모든 조건 충족 → 승인"},
            {"step": 3, "title": "의료비 · 진료비 영수증 한 장이 같은 화면에서 판정", "support_type": "medical", "file": files[2][0],
             "form": {"target_name": emp["name"], "relationship": "본인", "hospital_name": ok_receipt["hospital_name"], "receipt_date": iso(rd), "requested_amount": 47300},
             "expect": "치료 목적 진료 → 승인"},
            {"step": 4, "title": "(선택) 의료비 · 성형 수술 영수증 → 반려", "support_type": "medical", "file": files[3][0],
             "form": {"target_name": emp["name"], "relationship": "본인", "hospital_name": cos_receipt["hospital_name"], "receipt_date": iso(rd), "requested_amount": 1500000},
             "expect": "미용·성형 목적 → 반려"},
            {"step": 5, "title": "(선택) 학자금 · 자녀 · 기숙사비 포함 증명서 + 가족관계증명서", "support_type": "tuition", "file": [files[4][0], files[5][0]],
             "form": {"target_name": child, "relationship": "자녀", "school_name": "새솔대학교", "semester": "2026-2", "requested_amount": 4550000},
             "expect": "비대상 항목(기숙사비) 포함 → 반려"},
        ],
    }
    (DEMO / "demo_guide.json").write_text(json.dumps(guide, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"demo: {len(files)} files -> {DEMO} (기준일 {today})")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--demo-only", action="store_true")
    ap.add_argument("--today", default=None, help="데모 증빙의 기준일(YYYY-MM-DD). 기본: 오늘")
    args = ap.parse_args()
    if not args.demo_only:
        build_golden()
    build_demo(date.fromisoformat(args.today) if args.today else date.today())
