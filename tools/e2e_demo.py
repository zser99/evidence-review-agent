"""배포된 시스템에 대한 종단 간(E2E) 스모크 테스트 — 발표 데모 시나리오를 API로 그대로 재현한다.

    python tools/e2e_demo.py --base http://localhost:8080            # 백엔드 직접
    python tools/e2e_demo.py --base http://localhost:8088            # Nginx 경유(docker compose)
    python tools/e2e_demo.py --base https://<도메인> --evaluate       # 정답 세트 검증까지

시나리오
 1. 학자금: 신청 금액 ≠ 납입 금액인 납입증명서 → 보완 안내와 함께 반려
 2. 같은 신청 건에서 제대로 된 납입증명서로 교체 → 높은 확신도로 승인
 3. 의료비: 진료비 영수증 한 장이 같은 흐름으로 판정
 4. 의료비: 성형 수술 영수증 → 반려
 5. HR: 검토 대기 목록, 판정 근거 재조회, 최종 결정
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
DEMO = ROOT / "seed" / "demo"


class Api:
    def __init__(self, base: str):
        self.c = httpx.Client(base_url=base.rstrip("/"), timeout=180)
        self.token = None

    def login(self, username: str, password: str):
        r = self.c.post("/api/auth/login", json={"username": username, "password": password})
        r.raise_for_status()
        self.token = r.json()["token"]
        return r.json()["user"]

    def call(self, method: str, path: str, **kw):
        r = self.c.request(method, path, headers={"Authorization": f"Bearer {self.token}"}, **kw)
        if r.status_code >= 400:
            raise SystemExit(f"{method} {path} -> HTTP {r.status_code}: {r.text[:500]}")
        return r.json() if r.headers.get("content-type", "").startswith("application/json") else r.content

    def upload(self, app_id: int, doc_id: str, file: Path):
        with file.open("rb") as f:
            return self.call("POST", f"/api/applications/{app_id}/evidence", data={"docId": doc_id}, files={"file": (file.name, f)})


def show(title: str, d: dict):
    rv = d.get("review") or {}
    print(f"\n=== {title}")
    print(f"  상태: {d['statusLabel']}  | 자동 판정: {d.get('autoVerdict')}  | 확신도: {d.get('confidence')}")
    for c in rv.get("comparisons", []):
        print(f"  [대조] {c['label']}: {c['status']}  (신청 {c.get('leftDisplay')} ↔ 증빙 {c.get('rightDisplay')})")
    for s in rv.get("staticRules", []):
        print(f"  [규칙] {s['label']}: {s['status']}  ({s.get('clause')})")
    for j in rv.get("judgments", []):
        print(f"  [Jev ] {j['label']}: {j['decision']}  답={j.get('answerLabel')} 확신도={j.get('confidence')}  근거={','.join(j.get('clauseRefs') or [])}")
    m = rv.get("decisionModel") or {}
    print(f"  결정 모델: {m.get('provider')} / {m.get('model')}")
    if d.get("guidance"):
        print("  안내: " + d["guidance"].replace("\n", "\n        "))


def evaluate(hr: "Api"):
    """정답 세트 검증을 실행하고 발표용 지표를 출력한다."""
    run = hr.call("POST", "/api/evaluations")
    print(f"\n정답 세트 검증 시작(run {run['id']}) …", end="", flush=True)
    while True:
        time.sleep(5)
        r = hr.call("GET", f"/api/evaluations/{run['id']}")
        print(f" {r['progressDone']}/{r['progressTotal']}", end="", flush=True)
        if r["status"] != "RUNNING":
            break
    print()
    if r["status"] != "DONE":
        raise SystemExit("검증 실패: " + str(r.get("error")))
    s = r["summary"]
    v, e, j = s["verdict"], s["extraction"], s["judgments"]
    print(f"  정답 세트 {s['total']}건 (승인 {s['expected']['APPROVE']} / 반려 {s['expected']['REJECT']}), 결정 모델 {s['decisionModel']}, {s['elapsedSeconds']}초")
    print(f"  항목 추출 정확도   : {e['accuracy']:.1%} ({e['correct']}/{e['total']}, 오독 {e['wrong']}, 읽지 못함 {e['unread']})")
    print(f"  최종 판정 정확도   : 자동 판정분 {v['accuracyAuto']:.1%} ({v['correct']}/{v['correct'] + v['wrong']}), 전체 대비 {v['accuracyOverall']:.1%}")
    print(f"  담당자 확인 비율   : {v['reviewRate']:.1%} ({v['review']}건)  | 잘못된 승인 {v['falseApprove']} · 잘못된 반려 {v['falseReject']}")
    print(f"  반려 사유 일치율   : {s['reasonMatch']['rate']} ({s['reasonMatch']['matched']}/{s['reasonMatch']['total']})")
    print(f"  조건(Jev) 판정     : {j['accuracy']:.1%} ({j['correct']}/{j['total']})")
    cal = s.get("calibration") or {}
    print("  확신도 구간별 실제 정답률:")
    for b in cal.get("bins", []):
        print(f"    {b['range']}: n={b['count']:<4} 정답률={b['accuracy']}")
    print(f"  ECE={cal.get('ece')}  추천 기준값={cal.get('recommended')}")
    for cs in r["cases"]:
        if cs["outcome"] != "CORRECT":
            print(f"   - {cs['id']} {cs['title']}: 정답 {cs['expected']} → {cs['verdict']} ({cs['outcome']}) {cs['issueCodes']} {cs['reviewReasons']}")


def expect(cond: bool, msg: str, failures: list[str]):
    print(("  ✔ " if cond else "  ✘ ") + msg)
    if not cond:
        failures.append(msg)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--password", default="demo1234")
    ap.add_argument("--evaluate", action="store_true", help="정답 세트 검증까지 실행")
    ap.add_argument("--evaluate-only", action="store_true", help="데모 시나리오는 건너뛰고 정답 세트 검증만 실행")
    args = ap.parse_args()
    guide = json.loads((DEMO / "demo_guide.json").read_text(encoding="utf-8"))
    steps = {s["step"]: s for s in guide["steps"]}
    fails: list[str] = []

    if args.evaluate_only:
        hr = Api(args.base)
        hr.login("hr1", args.password)
        evaluate(hr)
        return
    emp = Api(args.base)
    user = emp.login("employee1", args.password)
    print(f"로그인: {user['name']} ({user['role']})")
    info = emp.call("GET", "/api/system/info")
    print("AI 구성:", json.dumps(info.get("ai", {}), ensure_ascii=False))
    types = emp.call("GET", "/api/support-types")
    print("지원 종류:", ", ".join(f"{t['name']} v{t['version']}" for t in types))

    # 1. 학자금 — 금액 불일치
    a = emp.call("POST", "/api/applications", json={"supportType": "tuition", "form": steps[1]["form"]})
    d = emp.upload(a["id"], "tuition_payment_certificate", DEMO / steps[1]["file"])
    f = d["documents"][0]["file"]
    print(f"\n업로드 직후 추출({f['engine']}, {f['elapsedMs']}ms): " + ", ".join(f"{x['label']}={x['display']}" for x in f["fields"] if x["status"] == "OK"))
    amount = next(c for c in d["precheck"]["comparisons"] if c["id"] == "C-AMOUNT")
    expect(amount["status"] == "MISMATCH", "업로드 즉시 금액 불일치 표시", fails)
    d = emp.call("POST", f"/api/applications/{a['id']}/submit")
    show("1. 학자금 · 금액 불일치", d)
    expect(d["autoVerdict"] == "REJECT" and "C-AMOUNT" in [i["code"] for i in d["review"]["issues"]], "금액 불일치로 반려 + 보완 안내", fails)

    # 2. 같은 건에서 증빙 교체 → 승인
    emp.upload(a["id"], "tuition_payment_certificate", DEMO / steps[2]["file"])
    d = emp.call("POST", f"/api/applications/{a['id']}/submit")
    show("2. 학자금 · 정상 증빙으로 교체 후 재제출", d)
    expect(d["autoVerdict"] == "APPROVE" and d["status"] == "AUTO_APPROVED", "높은 확신도로 승인", fails)
    expect(len(d["runs"]) == 2, "검수 이력 2회 보관", fails)

    # 3. 의료비 — 같은 흐름
    m = emp.call("POST", "/api/applications", json={"supportType": "medical", "form": steps[3]["form"]})
    emp.upload(m["id"], "medical_receipt", DEMO / steps[3]["file"])
    d = emp.call("POST", f"/api/applications/{m['id']}/submit")
    show("3. 의료비 · 진료비 영수증", d)
    expect(d["autoVerdict"] == "APPROVE", "의료비 영수증 승인(같은 판정 흐름)", fails)

    # 4. 의료비 — 성형
    m2 = emp.call("POST", "/api/applications", json={"supportType": "medical", "form": steps[4]["form"]})
    emp.upload(m2["id"], "medical_receipt", DEMO / steps[4]["file"])
    d = emp.call("POST", f"/api/applications/{m2['id']}/submit")
    show("4. 의료비 · 성형 수술 영수증", d)
    expect(d["autoVerdict"] == "REJECT" and "J-MED-PURPOSE" in [i["code"] for i in d["review"]["issues"]], "미용·성형 목적으로 반려", fails)

    # 5. 자녀 학자금 — 서류 누락 → 가족관계증명서 추가 → 비대상 항목 반려
    c = emp.call("POST", "/api/applications", json={"supportType": "tuition", "form": steps[5]["form"]})
    d = emp.upload(c["id"], "tuition_payment_certificate", DEMO / steps[5]["file"][0])
    expect(any(x["id"] == "family_relation_certificate" for x in d["precheck"]["missingDocuments"]), "빠진 서류(가족관계증명서) 즉시 안내", fails)
    emp.upload(c["id"], "family_relation_certificate", DEMO / steps[5]["file"][1])
    d = emp.call("POST", f"/api/applications/{c['id']}/submit")
    show("5. 학자금 · 자녀 · 기숙사비 포함", d)
    expect(d["autoVerdict"] == "REJECT" and "J-ITEMS" in [i["code"] for i in d["review"]["issues"]], "비대상 항목 포함으로 반려", fails)

    # 권한: 다른 구성원은 볼 수 없다
    other = Api(args.base)
    other.login("employee2", args.password)
    r = other.c.get(f"/api/applications/{a['id']}", headers={"Authorization": f"Bearer {other.token}"})
    expect(r.status_code == 404, "다른 구성원의 신청 건은 조회 불가", fails)
    r = other.c.get("/api/review/queue", headers={"Authorization": f"Bearer {other.token}"})
    expect(r.status_code == 403, "구성원은 검토 대기 목록 접근 불가", fails)

    # HR
    hr = Api(args.base)
    hr.login("hr1", args.password)
    q = hr.call("GET", "/api/review/queue", params={"kind": "all"})
    print(f"\nHR 검토 대기 {len(q)}건: " + ", ".join(f"{x['applicationNo']}({x['autoVerdict']},{x['confidence']})" for x in q[:6]))
    expect(any(x["id"] == m2["id"] for x in q), "반려 건이 HR 대기 목록에 있음", fails)
    snap = hr.call("GET", f"/api/applications/{a['id']}/runs/1")
    expect(snap["verdict"] == "REJECT", "과거 판정 근거(1회차 스냅샷) 재조회", fails)
    d = hr.call("POST", f"/api/review/applications/{m2['id']}/decision", json={"decision": "REJECT", "comment": "미용 목적 수술로 확인"})
    expect(d["status"] == "REJECTED", "HR 최종 반려 확정", fails)

    if args.evaluate:
        evaluate(hr)

    print("\n" + ("모든 확인 통과" if not fails else f"실패 {len(fails)}건: {fails}"))
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
