from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # --- 내부 호출 보호 (Spring Boot → FastAPI) ---
    ai_internal_token: str = ""

    # --- OCR ---
    ocr_engine: str = "rapidocr"                     # rapidocr(ONNX Runtime) | paddle(네이티브) | none(텍스트 PDF만)
    ocr_det_model: str = "PP-OCRv5_mobile_det"
    ocr_rec_model: str = "korean_PP-OCRv5_mobile_rec"
    ocr_enable_mkldnn: bool = False                  # 일부 CPU/OS 조합에서 oneDNN 오류가 있어 기본 비활성
    ocr_det_limit_side_len: int = 1280            # 검출 입력의 긴 변(px). 정답 세트에서 1280이 가장 정확·빠름
    ocr_max_pages: int = 5
    ocr_preload: bool = True

    # --- 결정 모델 ---
    decision_provider: str = "auto"                  # auto | jev | openai | local
    jev_api_key: str = ""
    jev_base_url: str = "https://api.aimlapi.com"
    jev_path: str = "/v1/decisions"
    jev_model: str = "typesafe/jev-1.14"             # 임계값을 보정한 버전에 고정(pin)
    jev_timeout_seconds: float = 30.0

    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    openai_chat_model: str = "gpt-4o-mini"           # logprobs 지원 모델
    openai_embedding_model: str = "text-embedding-3-small"
    openai_timeout_seconds: float = 40.0

    local_temperature: float = 0.07                  # 로컬 대체 분류기의 softmax 온도(정답 세트로 보정)

    # --- RAG ---
    embedding_provider: str = "auto"                 # auto | openai | hash
    qdrant_url: str = ""                             # 비우면 프로세스 내장(in-memory) 모드
    qdrant_api_key: str = ""
    qdrant_collection: str = "regulations"
    rag_top_k: int = 2


@lru_cache
def get_settings() -> Settings:
    return Settings()
