"""증빙 검수 AI 서버 (FastAPI).

Spring Boot(업무 규칙)에서만 호출하는 내부 서비스다.
  /v1/extract          증빙 → 항목·표 추출(원본 옆에 표시할 위치 포함)
  /v1/judge            규정 조건 → 결정 모델(Jev) 판정: 충족 여부·확신도·근거 조항
  /v1/policies/index   규정집 → 조항 단위 색인(Qdrant)
  /v1/calibrate        확신도 구간별 실제 정답률·기준값 추천
  /v1/guidance/polish  보완 안내 문구 다듬기(LLM, 선택)
  /v1/policies/draft   규정 조건 초안 생성(LLM, 선택)
"""
from __future__ import annotations

import base64
import json
import logging
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool

from . import calibration, llm
from .config import get_settings
from .decision import service as decision
from .ocr import engine as ocr_engine
from .ocr.extract import extract
from .rag.store import get_store
from .schemas import CalibrateRequest, DraftRequest, IndexRequest, JudgeRequest, PolishRequest, SearchRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger("ai-server")
MAX_UPLOAD = 15 * 1024 * 1024


@asynccontextmanager
async def lifespan(_: FastAPI):
    s = get_settings()
    if s.ocr_preload and s.ocr_engine != "none":
        await run_in_threadpool(ocr_engine.preload)
    log.info("AI 서버 시작: ocr=%s decision=%s", s.ocr_engine, decision.provider_kind())
    yield


app = FastAPI(title="증빙 검수 AI 서버", version="1.0.0", lifespan=lifespan)


def internal_only(x_internal_token: str | None = Header(default=None)):
    token = get_settings().ai_internal_token
    if token and x_internal_token != token:
        raise HTTPException(status_code=401, detail="내부 호출 토큰이 올바르지 않습니다.")


@app.get("/healthz")
async def healthz():
    return {"status": "ok"}


@app.get("/v1/info", dependencies=[Depends(internal_only)])
async def info():
    s = get_settings()
    store = get_store()
    provider = decision.get_provider()
    return {
        "ocr": {"engine": s.ocr_engine, "model": ocr_engine.engine_name()},
        "decision": {"provider": provider.name, "model": provider.model,
                     "jev_configured": bool(s.jev_api_key), "fallback": provider.name != "jev"},
        "rag": {"embedding": store.embedder.name, "qdrant": store.mode, "collection": store.collection},
        "llm": {"available": bool(s.openai_api_key), "model": s.openai_chat_model},
    }


@app.post("/v1/extract", dependencies=[Depends(internal_only)])
async def extract_document(file: UploadFile = File(...), spec: str = Form(...), include_pages: bool = Form(True)):
    """spec: 지원 종류 YAML의 documents[*] 한 항목(JSON)."""
    data = await file.read()
    if len(data) > MAX_UPLOAD:
        raise HTTPException(status_code=413, detail="파일이 너무 큽니다(최대 15MB).")
    try:
        doc = json.loads(spec)
    except json.JSONDecodeError as e:
        raise HTTPException(status_code=400, detail="spec이 올바른 JSON이 아닙니다.") from e
    t0 = time.time()
    try:
        pages, boxes, engine = await run_in_threadpool(ocr_engine.load_document, data)
    except ocr_engine.OcrUnavailable as e:
        raise HTTPException(status_code=503, detail=str(e)) from e
    except ValueError as e:
        raise HTTPException(status_code=415, detail=str(e)) from e
    result = extract(doc, boxes, pages[0].height if pages else 1)
    quality = sum(b.conf for b in boxes) / len(boxes) if boxes else 0.0
    return {
        "doc_id": doc.get("id"), "engine": engine, "elapsed_ms": int((time.time() - t0) * 1000),
        "quality": round(quality, 4), "box_count": len(boxes), **result,
        "lines": [b.to_dict() for b in boxes],
        "pages": [{"index": p.index, "width": p.width, "height": p.height,
                   "image_jpeg_b64": base64.b64encode(p.jpeg).decode("ascii") if include_pages else None} for p in pages],
    }


@app.post("/v1/judge", dependencies=[Depends(internal_only)])
async def judge(req: JudgeRequest):
    try:
        return await decision.judge(req)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e


@app.post("/v1/policies/index", dependencies=[Depends(internal_only)])
async def index_policy(req: IndexRequest):
    try:
        clauses = await get_store().index(req.support_type, req.version, req.effective_date, req.regulation_text)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    return {"support_type": req.support_type, "version": req.version, "clause_count": len(clauses), "clauses": clauses,
            "embedding": get_store().embedder.name}


@app.get("/v1/policies/indexed", dependencies=[Depends(internal_only)])
async def indexed(support_type: str, version: str):
    return {"support_type": support_type, "version": version, "clause_count": await get_store().count(support_type, version)}


@app.post("/v1/clauses/search", dependencies=[Depends(internal_only)])
async def search_clauses(req: SearchRequest):
    return {"results": await get_store().search(req.support_type, req.version, req.query, req.top_k)}


@app.post("/v1/calibrate", dependencies=[Depends(internal_only)])
async def calibrate(req: CalibrateRequest):
    return calibration.calibrate([s.model_dump() for s in req.samples], req.target_accuracy, req.bins, req.min_samples)


@app.post("/v1/guidance/polish", dependencies=[Depends(internal_only)])
async def polish(req: PolishRequest):
    try:
        return {"available": True, "text": await llm.polish_guidance(req.draft, req.verdict, req.pii_names)}
    except llm.LlmUnavailable as e:
        return {"available": False, "text": req.draft, "reason": str(e)}


@app.post("/v1/policies/draft", dependencies=[Depends(internal_only)])
async def draft(req: DraftRequest):
    try:
        return {"available": True, **(await llm.draft_judgments(req.support_type, req.regulation_text))}
    except llm.LlmUnavailable as e:
        return {"available": False, "reason": str(e)}
