import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    minio_endpoint: str = os.getenv("MINIO_ENDPOINT", "127.0.0.1:9000")
    minio_access_key: str = os.getenv("MINIO_ACCESS_KEY", "minioadmin")
    minio_secret_key: str = os.getenv("MINIO_SECRET_KEY", "minioadmin")
    minio_bucket: str = os.getenv("MINIO_BUCKET", "loadflex")
    minio_secure: bool = os.getenv("MINIO_SECURE", "false").lower() == "true"
    temp_dir: str = os.getenv("ALGORITHM_TEMP_DIR", "../runtime/tmp")


settings = Settings()
