"""Qdrant 규정 조항 저장소. (지원 종류, 버전) 메타데이터 필터로 '당시 적용 규정'만 검색한다."""
from __future__ import annotations

import logging
import uuid

from qdrant_client import AsyncQdrantClient
from qdrant_client import models as qm

from ..config import get_settings
from .chunker import split_clauses
from .embeddings import get_embedder

log = logging.getLogger("rag")
_NS = uuid.UUID("6f1d3c52-7c1e-4f0e-9a55-2b8a4a0f9d11")


class RegulationStore:
    def __init__(self) -> None:
        s = get_settings()
        self.embedder = get_embedder()
        if s.qdrant_url:
            self.client = AsyncQdrantClient(url=s.qdrant_url, api_key=s.qdrant_api_key or None, timeout=20)
            self.mode = s.qdrant_url
        else:
            self.client = AsyncQdrantClient(location=":memory:")
            self.mode = "in-memory"
        # 임베딩 차원이 다른 모델끼리 섞이지 않게 컬렉션을 임베딩별로 분리
        self.collection = f"{s.qdrant_collection}_{self.embedder.name}".replace("-", "_").replace(".", "_")
        self._ready = False

    async def _ensure(self) -> None:
        if self._ready:
            return
        if not await self.client.collection_exists(self.collection):
            await self.client.create_collection(
                self.collection, vectors_config=qm.VectorParams(size=self.embedder.dim, distance=qm.Distance.COSINE))
            for f in ("support_type", "version", "clause_id"):
                try:
                    await self.client.create_payload_index(self.collection, field_name=f,
                                                           field_schema=qm.PayloadSchemaType.KEYWORD)
                except Exception as e:  # noqa: BLE001 (in-memory 모드는 인덱스를 지원하지 않음)
                    log.debug("payload index 생략: %s", e)
        self._ready = True

    @staticmethod
    def _filter(support_type: str, version: str, clause_ids: list[str] | None = None, exclude: list[str] | None = None):
        must = [qm.FieldCondition(key="support_type", match=qm.MatchValue(value=support_type)),
                qm.FieldCondition(key="version", match=qm.MatchValue(value=version))]
        if clause_ids:
            must.append(qm.FieldCondition(key="clause_id", match=qm.MatchAny(any=clause_ids)))
        must_not = [qm.FieldCondition(key="clause_id", match=qm.MatchAny(any=exclude))] if exclude else None
        return qm.Filter(must=must, must_not=must_not)

    async def index(self, support_type: str, version: str, effective_date: str, text: str) -> list[dict]:
        await self._ensure()
        clauses = split_clauses(text)
        if not clauses:
            raise ValueError("규정집에서 '제N조(제목)' 형식의 조항을 찾지 못했습니다.")
        vectors = await self.embedder.embed([f"{c['clause_id']}({c['title']}) {c['text']}" for c in clauses])
        # 같은 (지원 종류, 버전)을 다시 올리면 조항을 통째로 교체한다
        await self.client.delete(self.collection, points_selector=qm.FilterSelector(filter=self._filter(support_type, version)))
        points = [
            qm.PointStruct(
                id=str(uuid.uuid5(_NS, f"{support_type}:{version}:{c['clause_id']}")), vector=v,
                payload={"support_type": support_type, "version": version, "effective_date": effective_date, **c})
            for c, v in zip(clauses, vectors)
        ]
        await self.client.upsert(self.collection, points=points, wait=True)
        log.info("규정 색인: %s v%s → 조항 %d개 (%s, %s)", support_type, version, len(points), self.embedder.name, self.mode)
        return clauses

    async def count(self, support_type: str, version: str) -> int:
        await self._ensure()
        r = await self.client.count(self.collection, count_filter=self._filter(support_type, version), exact=True)
        return r.count

    async def retrieve(self, support_type: str, version: str, query: str, clause_refs: list[str], top_k: int) -> list[dict]:
        """지정 조항(clause_refs)은 ID로 정확히 가져오고, 추가로 의미 검색 상위 top_k개를 덧붙인다."""
        await self._ensure()
        out: list[dict] = []
        if clause_refs:
            pts, _ = await self.client.scroll(self.collection, scroll_filter=self._filter(support_type, version, clause_refs),
                                              limit=len(clause_refs) + 5, with_payload=True)
            by_id = {p.payload["clause_id"]: p.payload for p in pts}
            for cid in clause_refs:
                if cid in by_id:
                    out.append({**_pub(by_id[cid]), "source": "지정 조항", "score": 1.0})
        if top_k > 0 and query.strip():
            vec = (await self.embedder.embed([query]))[0]
            res = await self.client.query_points(
                self.collection, query=vec, limit=top_k, with_payload=True,
                query_filter=self._filter(support_type, version, exclude=clause_refs or None))
            for p in res.points:
                out.append({**_pub(p.payload), "source": "의미 검색", "score": round(float(p.score), 4)})
        return out

    async def search(self, support_type: str, version: str, query: str, top_k: int = 5) -> list[dict]:
        return await self.retrieve(support_type, version, query, [], top_k)


def _pub(payload: dict) -> dict:
    return {"clause_id": payload["clause_id"], "title": payload["title"], "text": payload["text"],
            "version": payload["version"], "effective_date": payload.get("effective_date")}


_store: RegulationStore | None = None


def get_store() -> RegulationStore:
    global _store
    if _store is None:
        _store = RegulationStore()
    return _store
