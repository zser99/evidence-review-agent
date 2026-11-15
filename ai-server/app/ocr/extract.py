"""글자 상자에서 문서 정의(지원 종류 YAML의 documents[*])대로 항목과 표를 뽑는다.

규칙 기반(라벨 → 오른쪽/아래 값, 표 머리글 → 열 정렬)이라 같은 입력에 항상 같은 결과가 나오고,
개인정보가 담긴 원문을 외부 모델로 보내지 않는다.
"""
from __future__ import annotations

import re
from typing import Any

from .engine import Box
from .normalize import clean_text, mask_rrn, norm_label, norm_text, parse_value

MISSING = {"value": None, "raw": None, "confidence": 0.0, "bbox": None, "page": None, "found": False}


def _rows(boxes: list[Box]) -> list[list[Box]]:
    """같은 줄(y가 겹치는)의 상자를 행으로 묶고 왼쪽→오른쪽으로 정렬한다."""
    rows: list[list[Box]] = []
    for b in sorted(boxes, key=lambda b: (b.page, b.cy, b.x0)):
        placed = False
        for row in reversed(rows[-6:]):
            ref = row[0]
            if ref.page != b.page:
                continue
            top, bot = min(r.y0 for r in row), max(r.y1 for r in row)
            overlap = min(bot, b.y1) - max(top, b.y0)
            if overlap > 0.5 * min(b.h, bot - top):
                row.append(b)
                placed = True
                break
        if not placed:
            rows.append([b])
    for row in rows:
        row.sort(key=lambda b: b.x0)
    rows.sort(key=lambda r: (r[0].page, min(b.y0 for b in r)))
    return rows


def _all_aliases(doc: dict) -> set[str]:
    out: set[str] = set()
    for f in doc.get("fields", []):
        out.update(norm_label(a) for a in f.get("labels", []) or [])
    for t in doc.get("tables", []):
        for c in t.get("columns", []):
            out.update(norm_label(a) for a in c.get("header", []) or [])
    return {a for a in out if a}


def _field_result(value: Any, raw: str | None, box: Box | None, conf: float | None = None) -> dict:
    return {
        "value": value, "raw": raw, "found": value is not None,
        "confidence": round(conf if conf is not None else (box.conf if box else 0.0), 4),
        "bbox": [round(box.x0, 1), round(box.y0, 1), round(box.x1, 1), round(box.y1, 1)] if box else None,
        "page": box.page if box else None,
    }


def _strip_trailing_label(text: str, aliases: set[str]) -> str:
    """옆 칸 라벨이 한 상자로 붙어 읽힌 경우('2026학년도 제2학기실납입액') 꼬리의 라벨을 떼어낸다."""
    n = norm_text(text)
    for a in sorted(aliases, key=len, reverse=True):
        if len(a) >= 3 and n.endswith(a) and len(n) - len(a) >= 2:
            # 원문에서 라벨 글자 수만큼(공백 무시) 뒤에서 잘라낸다
            cut, i = len(a), len(text)
            while cut and i:
                i -= 1
                if not text[i].isspace():
                    cut -= 1
            return text[:i].rstrip()
    return text


def _find_labeled(field: dict, rows: list[list[Box]], aliases: set[str]) -> dict:
    ftype = field.get("type", "text")
    labels = [norm_label(a) for a in field.get("labels", [])]
    # (우선순위, 행번호, 상자, 같은 상자 안의 값 후보)
    cands: list[tuple[int, int, Box, str | None]] = []
    for ri, row in enumerate(rows):
        for b in row:
            n = norm_label(b.text)
            for li, a in enumerate(labels):
                if n == a:
                    cands.append((li, ri, b, None))
                elif n.startswith(a) and len(a) >= 2:
                    rest = b.text
                    # 라벨 글자만큼 앞에서 건너뛴 나머지를 값으로 본다 ('발급일 : 2026년 9월 1일')
                    k, i = len(a), 0
                    while k and i < len(rest):
                        if norm_label(rest[i]):
                            k -= 1
                        i += 1
                    # 글자형 값은 라벨 뒤에 구분자(공백·콜론)가 있을 때만 같은 상자 안의 값으로 인정한다
                    if ftype not in ("text", "person_name") or rest[i:i + 1] in (" ", ":", "：", "	"):
                        cands.append((100 + li, ri, b, rest[i:]))
                elif n.endswith(a) and len(a) >= 3:
                    cands.append((200 + li, ri, b, None))
    cands.sort(key=lambda c: (c[0], c[1]))
    for _prio, ri, lb, inline in cands:
        if inline is not None:
            v = parse_value(inline, ftype)
            if v is not None:
                return _field_result(v, clean_text(inline), lb)
            continue
        row = rows[ri]
        right = [b for b in row if b.x0 >= lb.x1 - lb.h * 0.3 and b is not lb]
        target: Box | None = None
        if right:
            nb = right[0]
            if norm_label(nb.text) not in aliases:
                target = nb
        if target is None and not right:
            # 오른쪽에 아무것도 없으면 바로 아래 줄에서 x가 겹치는 상자
            for row2 in rows[ri + 1: ri + 3]:
                below = [b for b in row2 if b.page == lb.page and min(b.x1, lb.x1) - max(b.x0, lb.x0) > 0
                         and b.y0 - lb.y1 < lb.h * 2.5]
                if below:
                    if norm_label(below[0].text) not in aliases:
                        target = below[0]
                    break
        if target is None:
            continue
        raw = target.text
        if ftype in ("text", "person_name"):
            raw = _strip_trailing_label(raw, aliases)
        v = parse_value(raw, ftype)
        if v is not None:
            return _field_result(v, clean_text(raw), target)
    return dict(MISSING)


def _find_pattern(field: dict, boxes: list[Box], rows: list[list[Box]]) -> dict:
    ftype = field.get("type", "text")
    pats = [re.compile(p) for p in field.get("patterns", [])]
    for pat in pats:
        for b in boxes:
            m = pat.search(b.text) or pat.search(norm_text(b.text))
            if m:
                if ftype == "presence":
                    return _field_result("있음", m.group(0), b)
                raw = m.group(1) if m.groups() else m.group(0)
                v = parse_value(raw, ftype)
                if v is not None:
                    return _field_result(v, clean_text(raw), b)
    if ftype == "presence":
        # '없음'도 확인된 값이다(해당 표기가 문서 어디에도 없음). 신뢰도는 문서 전체 인식 신뢰도의 평균.
        conf = sum(b.conf for b in boxes) / len(boxes) if boxes else 0.0
        return {"value": "없음", "raw": None, "found": True, "confidence": round(conf, 4), "bbox": None, "page": None}
    return dict(MISSING)


def _extract_table(spec: dict, rows: list[list[Box]]) -> tuple[list[dict], dict | None]:
    cols = spec.get("columns", [])
    heads = [[norm_label(a) for a in c.get("header", [])] for c in cols]
    ends = [norm_label(e) for e in spec.get("end_labels", [])]
    for ri, row in enumerate(rows):
        idx: dict[int, Box] = {}
        for ci, hs in enumerate(heads):
            for b in row:
                if norm_label(b.text) in hs and b not in idx.values():
                    idx[ci] = b
                    break
        if len(idx) < len(cols):
            continue
        # 머리글 행의 모든 상자를 열 기준점으로 쓴다(정의하지 않은 열의 값이 섞이지 않도록)
        anchors = sorted(row, key=lambda b: b.cx)
        named = {id(b): ci for ci, b in idx.items()}
        out: list[dict] = []
        last_y = max(b.y1 for b in row)
        row_h = max(b.h for b in row)
        page = row[0].page
        for row2 in rows[ri + 1:]:
            if row2[0].page != page:
                break
            top = min(b.y0 for b in row2)
            if top - last_y > row_h * 3.2:
                break
            first = norm_label(row2[0].text)
            if any(first == e or first.startswith(e) for e in ends):
                break
            cells: dict[int, list[Box]] = {}
            for b in row2:
                a = min(anchors, key=lambda h: abs(h.cx - b.cx))
                # 숫자 열은 오른쪽 정렬이 많으므로 열 범위(이웃 열과의 중간선)로 다시 확인
                ci = named.get(id(a))
                if ci is None:
                    continue
                cells.setdefault(ci, []).append(b)
            rec: dict[str, Any] = {}
            confs: list[float] = []
            boxes_in_row: list[Box] = []
            for ci, c in enumerate(cols):
                bs = cells.get(ci, [])
                raw = " ".join(b.text for b in bs)
                rec[c["key"]] = parse_value(raw, c.get("type", "text")) if bs else None
                confs += [b.conf for b in bs]
                boxes_in_row += bs
            last_y = max(b.y1 for b in row2)
            if rec.get(cols[0]["key"]) is None:
                continue
            rec["_confidence"] = round(min(confs), 4) if confs else 0.0
            if boxes_in_row:
                rec["_bbox"] = [round(min(b.x0 for b in boxes_in_row), 1), round(min(b.y0 for b in boxes_in_row), 1),
                                round(max(b.x1 for b in boxes_in_row), 1), round(max(b.y1 for b in boxes_in_row), 1)]
                rec["_page"] = page
            out.append(rec)
        head_box = {"bbox": [round(min(b.x0 for b in row), 1), round(min(b.y0 for b in row), 1),
                             round(max(b.x1 for b in row), 1), round(max(b.y1 for b in row), 1)], "page": page}
        return out, head_box
    return [], None


def _from_table(field: dict, tables: dict[str, list[dict]]) -> dict:
    ft = field["from_table"]
    rows = tables.get(ft["table"], [])
    where = ft.get("where", {})
    hits = []
    for r in rows:
        ok = True
        for k, want in where.items():
            wants = want if isinstance(want, list) else [want]
            if norm_text(str(r.get(k) or "")) not in [norm_text(str(w)) for w in wants]:
                ok = False
        if ok and r.get(ft["column"]) is not None:
            hits.append(r)
    if not hits:
        return dict(MISSING)
    if ft.get("many"):
        vals = [h[ft["column"]] for h in hits]
        return {"value": vals, "raw": ", ".join(map(str, vals)), "found": True,
                "confidence": min(h["_confidence"] for h in hits), "bbox": hits[0].get("_bbox"), "page": hits[0].get("_page")}
    h = hits[0]
    return {"value": h[ft["column"]], "raw": str(h[ft["column"]]), "found": True, "confidence": h["_confidence"],
            "bbox": h.get("_bbox"), "page": h.get("_page")}


def check_title(doc: dict, boxes: list[Box], page_height: float) -> dict:
    """문서 상단의 제목으로 '올린 서류가 요구한 서류 종류가 맞는지' 확인한다."""
    top = [b for b in boxes if b.page == 0 and b.cy < page_height * 0.3]
    top.sort(key=lambda b: -b.h)
    title = clean_text(top[0].text) if top else None
    joined = "".join(norm_label(b.text) for b in sorted(top, key=lambda b: (b.cy, b.x0)))
    for kw in doc.get("reject_title_keywords", []) or []:
        if norm_label(kw) in joined:
            return {"matched": False, "title": title, "reason": f"'{kw}'은(는) 인정되지 않는 서류입니다."}
    for kw in doc.get("title_keywords", []) or []:
        if norm_label(kw) in joined:
            return {"matched": True, "title": title, "reason": None}
    if not doc.get("title_keywords"):
        return {"matched": True, "title": title, "reason": None}
    return {"matched": False, "title": title, "reason": f"'{doc.get('name', '요구 서류')}'(으)로 확인되지 않는 서류입니다."}


def extract(doc: dict, boxes: list[Box], page_height: float) -> dict:
    for b in boxes:
        b.text = mask_rrn(b.text)
    rows = _rows(boxes)
    aliases = _all_aliases(doc)
    tables: dict[str, list[dict]] = {}
    table_meta: dict[str, Any] = {}
    for t in doc.get("tables", []) or []:
        tables[t["key"]], table_meta[t["key"]] = _extract_table(t, rows)
    fields: dict[str, dict] = {}
    for f in doc.get("fields", []) or []:
        if "from_table" in f:
            r = _from_table(f, tables)
        elif f.get("labels"):
            r = _find_labeled(f, rows, aliases)
            if not r["found"] and f.get("patterns"):
                r = _find_pattern(f, boxes, rows)
        elif f.get("patterns"):
            r = _find_pattern(f, boxes, rows)
        else:
            r = dict(MISSING)
        fields[f["key"]] = r
    title = check_title(doc, boxes, page_height)
    return {"doc_check": title, "fields": fields, "tables": tables, "table_meta": table_meta}
