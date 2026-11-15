# 증빙 검수 AI 서버 (FastAPI) — inference pod
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# OpenCV 런타임 라이브러리
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

# OCR 모델(PP-OCRv5 검출 + 한국어 인식)을 빌드할 때 받아 이미지에 넣는다.
# → 실행 중에는 외부에서 아무것도 받지 않으므로 폐쇄망·외부 차단 환경에서도 그대로 동작한다.
COPY app/__init__.py app/config.py ./app/
COPY app/ocr ./app/ocr
RUN python -c "from app.ocr.engine import warmup; n = warmup(); assert n > 0, 'OCR warmup read nothing'; print('OCR models ready, lines:', n)" \
 && ls -la /usr/local/lib/python3.11/site-packages/rapidocr/models/

COPY app ./app

RUN useradd --uid 10001 --create-home appuser && chown -R appuser /app
USER 10001
ENV HOME=/tmp

EXPOSE 8000
# OCR 예측기는 프로세스당 하나를 직렬로 쓰므로 워커는 1개, 처리량은 pod 수로 늘린다.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
