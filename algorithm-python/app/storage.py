import hashlib
import json
import os
from pathlib import Path
from typing import Any, Dict, Tuple

from minio import Minio

from app.config import settings


class ObjectStorage:
    def __init__(self) -> None:
        self.client = Minio(
            settings.minio_endpoint,
            access_key=settings.minio_access_key,
            secret_key=settings.minio_secret_key,
            secure=settings.minio_secure,
        )
        if not self.client.bucket_exists(settings.minio_bucket):
            self.client.make_bucket(settings.minio_bucket)

    def download(self, object_key: str, task_id: int) -> Path:
        temp_directory = Path(settings.temp_dir).resolve() / str(task_id)
        temp_directory.mkdir(parents=True, exist_ok=True)
        suffix = Path(object_key).suffix or ".csv"
        target_path = temp_directory / f"input{suffix}"
        self.client.fget_object(settings.minio_bucket, object_key, str(target_path))
        return target_path

    def upload_result(self, result: Dict[str, Any], task_id: int, task_type: str) -> Tuple[str, str]:
        temp_directory = Path(settings.temp_dir).resolve() / str(task_id)
        temp_directory.mkdir(parents=True, exist_ok=True)
        result_path = temp_directory / "result.json"
        result_bytes = json.dumps(result, ensure_ascii=False, indent=2, default=str).encode("utf-8")
        result_path.write_bytes(result_bytes)
        checksum = hashlib.sha256(result_bytes).hexdigest()
        object_key = f"results/{task_id}/{task_type.lower()}/result.json"
        self.client.fput_object(
            settings.minio_bucket,
            object_key,
            str(result_path),
            content_type="application/json",
        )
        return object_key, checksum
