"""증빙 파일(PDF·이미지)을 페이지 이미지와 글자 상자(Box)로 바꾼다.

- 텍스트 레이어가 있는 PDF: PyMuPDF로 글자와 위치를 그대로 읽는다(OCR 불필요, 신뢰도 1.0).
- 스캔 PDF·이미지: PaddleOCR PP-OCRv5 한국어 모델로 읽는다. 원본은 이 서버 밖으로 나가지 않는다.
"""
from __future__ import annotations

import io
import logging
import os
import threading
from dataclasses import dataclass

import pymupdf as fitz
import numpy as np
from PIL import Image, ImageOps

from ..config import get_settings

log = logging.getLogger("ocr")
os.environ.setdefault("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True")

PDF_ZOOM = 2.0
MAX_SIDE = 2000


@dataclass
class Box:
    text: str
    conf: float
    x0: float
    y0: float
    x1: float
    y1: float
    page: int = 0

    @property
    def cx(self) -> float:
        return (self.x0 + self.x1) / 2

    @property
    def cy(self) -> float:
        return (self.y0 + self.y1) / 2

    @property
    def h(self) -> float:
        return self.y1 - self.y0

    def to_dict(self) -> dict:
        return {"text": self.text, "confidence": round(self.conf, 4), "page": self.page,
                "bbox": [round(self.x0, 1), round(self.y0, 1), round(self.x1, 1), round(self.y1, 1)]}


@dataclass
class PageImage:
    index: int
    width: int
    height: int
    jpeg: bytes


class OcrUnavailable(RuntimeError):
    pass


class _Engine:
    """OCR 엔진 단일 인스턴스. 예측기는 스레드 안전하지 않아 잠금으로 직렬화한다.

    - rapidocr(기본): PaddleOCR PP-OCRv5 검출 + 한국어 인식 모델을 ONNX Runtime으로 실행(CPU에서 장당 1~2초)
    - paddle: PaddleOCR 네이티브 실행(같은 모델, paddlepaddle 필요)
    """
    _inst = None
    _kind = ""
    _init_lock = threading.Lock()
    _run_lock = threading.Lock()

    @classmethod
    def get(cls):
        with cls._init_lock:
            if cls._inst is not None:
                return cls._inst
            s = get_settings()
            if s.ocr_engine == "rapidocr":
                from rapidocr import EngineType, LangDet, LangRec, ModelType, OCRVersion, RapidOCR

                log.info("RapidOCR(ONNX Runtime) 로딩: PP-OCRv5 det + korean rec")
                cls._inst = RapidOCR(params={
                    "Det.engine_type": EngineType.ONNXRUNTIME, "Det.lang_type": LangDet.CH,
                    "Det.model_type": ModelType.MOBILE, "Det.ocr_version": OCRVersion.PPOCRV5,
                    "Rec.engine_type": EngineType.ONNXRUNTIME, "Rec.lang_type": LangRec.KOREAN,
                    "Rec.model_type": ModelType.MOBILE, "Rec.ocr_version": OCRVersion.PPOCRV5,
                    "Det.limit_side_len": s.ocr_det_limit_side_len, "Det.limit_type": "max",
                    "Global.use_cls": False, "Global.log_level": "warning",
                })
            elif s.ocr_engine == "paddle":
                from paddleocr import PaddleOCR

                log.info("PaddleOCR 로딩: det=%s rec=%s", s.ocr_det_model, s.ocr_rec_model)
                cls._inst = PaddleOCR(
                    text_detection_model_name=s.ocr_det_model, text_recognition_model_name=s.ocr_rec_model,
                    use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
                    enable_mkldnn=s.ocr_enable_mkldnn, text_det_limit_side_len=s.ocr_det_limit_side_len,
                    text_det_limit_type="max",
                )
            else:
                raise OcrUnavailable("OCR 엔진이 비활성화되어 있습니다(OCR_ENGINE=none). 텍스트 PDF만 처리할 수 있습니다.")
            cls._kind = s.ocr_engine
            return cls._inst

    @classmethod
    def run(cls, img: Image.Image, page: int) -> list[Box]:
        ocr = cls.get()
        arr = np.asarray(img.convert("RGB"))
        boxes: list[Box] = []

        def add(text, score, poly):
            text = (text or "").strip()
            if text:
                p = np.asarray(poly, dtype=float).reshape(-1, 2)
                boxes.append(Box(text, float(score), float(p[:, 0].min()), float(p[:, 1].min()),
                                 float(p[:, 0].max()), float(p[:, 1].max()), page))

        with cls._run_lock:
            if cls._kind == "rapidocr":
                r = ocr(arr)
                if r is not None and getattr(r, "txts", None) is not None:
                    for text, score, poly in zip(r.txts, r.scores, r.boxes):
                        add(text, score, poly)
            else:
                for r in ocr.predict(arr):
                    polys = r.get("rec_polys")
                    if polys is None:
                        polys = r.get("dt_polys", [])
                    for text, score, poly in zip(r.get("rec_texts", []), r.get("rec_scores", []), polys):
                        add(text, score, poly)
        return boxes


def engine_name() -> str:
    s = get_settings()
    return {"rapidocr": "PP-OCRv5(korean) · ONNX Runtime", "paddle": "PaddleOCR PP-OCRv5(korean)"}.get(s.ocr_engine, "none")


def warmup() -> int:
    """글자가 있는 작은 이미지로 실제 추론을 한 번 돌린다.

    RapidOCR은 검출·인식 모델을 첫 호출 때 내려받아 올리므로, 이 함수를 이미지 빌드 때 실행해 모델을 이미지에 넣고
    (실행 중에는 외부 다운로드 없음) 서버 기동 때도 실행해 첫 요청이 느려지지 않게 한다.
    """
    from PIL import ImageDraw, ImageFont

    img = Image.new("RGB", (640, 160), "white")
    ImageDraw.Draw(img).text((30, 50), "OCR WARMUP 2026-01-01 1,234,000", fill="black", font=ImageFont.load_default(size=36))
    return len(_Engine.run(img, 0))


def preload():
    try:
        n = warmup()
        log.info("OCR 준비 완료(워밍업 인식 %d줄)", n)
    except Exception as e:  # noqa: BLE001
        log.warning("OCR 사전 로딩 실패: %s", e)


def _jpeg(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=82)
    return buf.getvalue()


def _fit(img: Image.Image) -> Image.Image:
    w, h = img.size
    m = max(w, h)
    if m > MAX_SIDE:
        s = MAX_SIDE / m
        img = img.resize((int(w * s), int(h * s)), Image.LANCZOS)
    return img


def _pdf_text_boxes(page: "fitz.Page", index: int) -> list[Box]:
    """텍스트 레이어의 단어를 '칸 단위' 상자로 묶는다(같은 줄에서 간격이 좁은 단어끼리 병합)."""
    words = page.get_text("words")  # x0, y0, x1, y1, word, block, line, wordno
    words = [w for w in words if w[4].strip()]
    words.sort(key=lambda w: (round((w[1] + w[3]) / 2 / 3), w[0]))
    boxes: list[Box] = []
    cur = None
    for x0, y0, x1, y1, text, *_ in words:
        h = y1 - y0
        if cur and abs((y0 + y1) / 2 - (cur[1] + cur[3]) / 2) < h * 0.5 and 0 <= x0 - cur[2] < h * 0.75:
            cur = [cur[0], min(cur[1], y0), x1, max(cur[3], y1), cur[4] + " " + text]
        elif cur and abs((y0 + y1) / 2 - (cur[1] + cur[3]) / 2) < h * 0.5 and -h * 0.3 < x0 - cur[2] < 0:
            cur = [cur[0], min(cur[1], y0), x1, max(cur[3], y1), cur[4] + text]
        else:
            if cur:
                boxes.append(Box(cur[4], 1.0, cur[0] * PDF_ZOOM, cur[1] * PDF_ZOOM, cur[2] * PDF_ZOOM, cur[3] * PDF_ZOOM, index))
            cur = [x0, y0, x1, y1, text]
    if cur:
        boxes.append(Box(cur[4], 1.0, cur[0] * PDF_ZOOM, cur[1] * PDF_ZOOM, cur[2] * PDF_ZOOM, cur[3] * PDF_ZOOM, index))
    return boxes


def load_document(data: bytes) -> tuple[list[PageImage], list[Box], str]:
    """(페이지 미리보기, 글자 상자, 사용 엔진)을 돌려준다. 상자 좌표는 미리보기 이미지의 픽셀 좌표."""
    s = get_settings()
    pages: list[PageImage] = []
    boxes: list[Box] = []
    engines: set[str] = set()
    if data[:5] == b"%PDF-":
        doc = fitz.open(stream=data, filetype="pdf")
        for i, page in enumerate(doc):
            if i >= s.ocr_max_pages:
                break
            pix = page.get_pixmap(matrix=fitz.Matrix(PDF_ZOOM, PDF_ZOOM), alpha=False)
            img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
            pages.append(PageImage(i, img.width, img.height, _jpeg(img)))
            tb = _pdf_text_boxes(page, i)
            if sum(len(b.text) for b in tb) >= 30:
                boxes += tb
                engines.add("pdf-text")
            else:
                boxes += _Engine.run(img, i)
                engines.add("ocr")
        doc.close()
    else:
        try:
            img = Image.open(io.BytesIO(data))
            img = ImageOps.exif_transpose(img).convert("RGB")
        except Exception as e:  # noqa: BLE001
            raise ValueError("지원하지 않는 파일 형식입니다. PDF, PNG, JPG만 올릴 수 있습니다.") from e
        img = _fit(img)
        pages.append(PageImage(0, img.width, img.height, _jpeg(img)))
        boxes += _Engine.run(img, 0)
        engines.add("ocr")
    return pages, boxes, "+".join(sorted(engines)) or "none"
