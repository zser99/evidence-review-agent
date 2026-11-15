"""규정 조항 임베딩. OpenAI 키가 있으면 OpenAI 임베딩, 없으면 외부 호출이 없는 해시 임베딩."""
from __future__ import annotations

import hashlib
import math
import re

import httpx

from ..config import get_settings


class HashEmbedding:
    """문자 n-gram 해싱 임베딩(외부 호출·모델 다운로드 없음). 규정 조항 수십 개 규모의 검색에 충분하다."""
    name = "hash-ngram-512"
    dim = 512

    def _vec(self, text: str) -> list[float]:
        t = re.sub(r"\s+", " ", text.strip())
        v = [0.0] * self.dim
        compact = t.replace(" ", "")
        grams = [compact[i:i + n] for n in (2, 3) for i in range(max(0, len(compact) - n + 1))]
        grams += [w for w in t.split(" ") if w]
        for g in grams:
            h = int.from_bytes(hashlib.blake2b(g.encode("utf-8"), digest_size=8).digest(), "big")
            v[h % self.dim] += 1.0 if (h >> 63) & 1 else -1.0
        norm = math.sqrt(sum(x * x for x in v)) or 1.0
        return [x / norm for x in v]

    async def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._vec(t) for t in texts]


class OpenAIEmbedding:
    dim = 1536

    def __init__(self) -> None:
        s = get_settings()
        self.model = s.openai_embedding_model
        self.name = f"openai-{self.model}"

    async def embed(self, texts: list[str]) -> list[list[float]]:
        s = get_settings()
        async with httpx.AsyncClient(timeout=s.openai_timeout_seconds) as c:
            r = await c.post(f"{s.openai_base_url}/embeddings", headers={"Authorization": f"Bearer {s.openai_api_key}"},
                             json={"model": self.model, "input": texts})
            r.raise_for_status()
            data = sorted(r.json()["data"], key=lambda d: d["index"])
            return [d["embedding"] for d in data]


def get_embedder():
    s = get_settings()
    use_openai = s.embedding_provider == "openai" or (s.embedding_provider == "auto" and s.openai_api_key)
    return OpenAIEmbedding() if use_openai else HashEmbedding()
