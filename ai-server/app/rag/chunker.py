"""규정집을 조항(제N조) 단위로 자른다. 조항 ID·제목·버전·시행일을 메타데이터로 붙인다."""
from __future__ import annotations

import re

_CLAUSE = re.compile(r"^\s*(?:#+\s*)?(제\s*\d+\s*조(?:의\s*\d+)?)\s*[(（]([^)）]*)[)）]", re.M)


def split_clauses(text: str) -> list[dict]:
    text = text.replace("\r\n", "\n")
    marks = list(_CLAUSE.finditer(text))
    out: list[dict] = []
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        body = text[m.start():end].strip()
        clause_id = re.sub(r"\s", "", m.group(1))
        out.append({"clause_id": clause_id, "title": m.group(2).strip(), "text": re.sub(r"\n{2,}", "\n", body),
                    "order": i})
    return out
