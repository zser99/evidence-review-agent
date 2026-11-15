"""외부 API(Jev, OpenAI)로 나가는 모든 값에 적용하는 개인정보 마스킹.

원칙
1. 판정에 필요한 항목(조건의 inputs)만 보낸다 — 증빙 원본·전체 OCR 텍스트는 보내지 않는다.
2. 성명은 요청 안에서 일관된 가명 토큰([이름A])으로 바꾼다.
3. 주민등록번호·전화번호·이메일·카드/계좌번호 형태의 문자열은 값의 유형과 무관하게 지운다.
"""
from __future__ import annotations

import re

_PATTERNS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"\d{6}\s*[-–]\s*[1-4][\d*]{6}"), "[주민등록번호]"),
    (re.compile(r"01[016789][-.\s]?\d{3,4}[-.\s]?\d{4}"), "[전화번호]"),
    (re.compile(r"0\d{1,2}[-.\s]\d{3,4}[-.\s]\d{4}"), "[전화번호]"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[이메일]"),
    (re.compile(r"(?<!\d)\d{4}[-\s]?[\d*]{4}[-\s]?[\d*]{4}[-\s]?\d{4}(?!\d)"), "[카드번호]"),
    # 날짜(2026-08-21)는 남기고, 그 밖의 '숫자-숫자-숫자' 형태(계좌·사업자·등록번호)는 지운다
    (re.compile(r"(?<![\d-])(?!(?:19|20)\d{2}-\d{1,2}-\d{1,2}(?!\d))\d{2,6}-\d{2,6}-\d{2,8}(?:-\d{1,4})?(?!\d)"), "[계좌·식별번호]"),
]
PII_TYPES = {"person_name", "name_list", "rrn", "identifier"}


class Masker:
    def __init__(self) -> None:
        self._names: dict[str, str] = {}

    def _token(self, name: str) -> str:
        key = re.sub(r"\s", "", name)
        if key not in self._names:
            n = len(self._names)
            self._names[key] = f"[이름{chr(ord('A') + n % 26)}{'' if n < 26 else n // 26}]"
        return self._names[key]

    def register_names(self, names) -> None:
        for n in names or []:
            if isinstance(n, str) and n.strip():
                self._token(n)

    def scrub(self, text: str) -> str:
        """자유 문자열에서 개인정보 패턴과 등록된 성명을 지운다."""
        out = text
        for pat, repl in _PATTERNS:
            out = pat.sub(repl, out)
        for name in sorted(self._names, key=len, reverse=True):
            if name and name in out:
                out = out.replace(name, self._names[name])
        return out

    def mask(self, value, ftype: str | None = None):
        if value is None:
            return None
        if ftype == "person_name":
            return self._token(str(value))
        if ftype == "name_list":
            return [self._token(str(v)) for v in (value if isinstance(value, list) else [value])]
        if ftype == "rrn":
            return "[주민등록번호]"
        if ftype == "identifier":
            return "[식별번호]"
        if isinstance(value, str):
            return self.scrub(value)
        if isinstance(value, list):
            return [self.mask(v) for v in value]
        if isinstance(value, dict):
            return {k: self.mask(v) for k, v in value.items()}
        return value

    def unmask(self, text: str) -> str:
        """LLM이 돌려준 문장에서 가명 토큰을 원래 성명으로 되돌린다(내부 화면 표시용)."""
        out = text
        for name, tok in self._names.items():
            out = out.replace(tok, name)
        return out

    @property
    def mapping(self) -> dict[str, str]:
        return dict(self._names)
