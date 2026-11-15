"""OCR로 읽은 문자열을 항목 유형별 값으로 정규화한다."""
from __future__ import annotations

import re
from datetime import date

_WS = re.compile(r"\s+")
_LABEL_STRIP = re.compile(r"[\s:：·ㆍ.\-_*■□▶/()（）\[\]]+")
_RRN = re.compile(r"(\d{6})\s*[-–]\s*([1-4])[\d*]{6}")


def norm_label(s: str) -> str:
    """라벨 비교용: 공백·구두점을 지운다. ('성  명 :' -> '성명')"""
    return _LABEL_STRIP.sub("", s or "")


def norm_text(s: str) -> str:
    """값 비교용: 공백을 모두 지운다."""
    return _WS.sub("", s or "")


def mask_rrn(s: str) -> str:
    """주민등록번호 뒷자리는 저장·전달하지 않는다."""
    return _RRN.sub(lambda m: f"{m.group(1)}-{m.group(2)}******", s or "")


def clean_text(s: str) -> str | None:
    s = _WS.sub(" ", s or "").strip(" :：|")
    return s or None


def parse_money(s: str) -> int | None:
    """금액. 자릿수 구분이 어긋난 값('1,66,000')은 잘못 읽은 것으로 보고 버린다(읽지 못함 처리)."""
    if not s:
        return None
    t = s.replace(" ", "")
    # OCR이 숫자 0을 영문 o/O로 읽는 경우(특히 '0' 한 글자)를 바로잡는다
    t = re.sub(r"(?<![A-Za-z가-힣])[oO○](?![A-Za-z가-힣])", "0", t)
    cands = re.findall(r"-?\d[\d,.]*\d|-?\d", t)
    if not cands:
        return None
    tok = max(cands, key=len)
    neg = tok.startswith("-")
    tok = tok.lstrip("-")
    if re.fullmatch(r"\d+", tok):
        v = int(tok)
    elif re.fullmatch(r"\d{1,3}(?:[,.]\d{3})+", tok):  # OCR이 ','를 '.'로 읽는 경우 포함
        v = int(re.sub(r"[,.]", "", tok))
    else:
        return None
    return -v if neg else v


def parse_date(s: str) -> str | None:
    if not s:
        return None
    m = re.search(r"(20\d{2}|19\d{2})\s*[년.\-/]\s*(\d{1,2})\s*[월.\-/]\s*(\d{1,2})", s)
    if not m:
        return None
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3))).isoformat()
    except ValueError:
        return None


def parse_semester(s: str) -> str | None:
    """'2026학년도 제2학기' -> '2026-2'. 계절학기는 정규 학기가 아니므로 None."""
    if not s:
        return None
    t = s.replace(" ", "")
    if "계절" in t:
        return None
    m = re.search(r"(20\d{2})(?:학년도|년도|년)?[-/.]?제?([12])학기", t)
    if m:
        return f"{m.group(1)}-{m.group(2)}"
    m = re.fullmatch(r"(20\d{2})[-/.]([12])", t)
    if m:
        return f"{m.group(1)}-{m.group(2)}"
    return None


def parse_person_name(s: str) -> str | None:
    if not s:
        return None
    t = re.sub(r"[^가-힣A-Za-z]", "", s)
    return t if 2 <= len(t) <= 20 else None


def parse_identifier(s: str) -> str | None:
    if not s:
        return None
    cands = re.findall(r"[0-9A-Za-z]+(?:\s*-\s*[0-9A-Za-z]+)*", s)
    cands = [re.sub(r"\s", "", c) for c in cands if any(ch.isdigit() for ch in c)]
    if not cands:
        return None
    return max(cands, key=len)


PARSERS = {
    "money": parse_money,
    "date": parse_date,
    "semester": parse_semester,
    "person_name": parse_person_name,
    "identifier": parse_identifier,
    "text": clean_text,
}


def parse_value(raw: str, ftype: str):
    return PARSERS.get(ftype, clean_text)(raw)
