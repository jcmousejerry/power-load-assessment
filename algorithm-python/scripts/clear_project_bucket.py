import os

from minio import Minio
from minio.deleteobjects import DeleteObject


endpoint = os.getenv("MINIO_ENDPOINT", "127.0.0.1:9000").removeprefix("http://").removeprefix("https://")
bucket = os.getenv("MINIO_BUCKET", "loadflex")
client = Minio(
    endpoint,
    access_key=os.getenv("MINIO_ACCESS_KEY", "minioadmin"),
    secret_key=os.getenv("MINIO_SECRET_KEY", "minioadmin"),
    secure=os.getenv("MINIO_SECURE", "false").lower() == "true",
)

if not client.bucket_exists(bucket):
    print(f"MinIO bucket {bucket} does not exist; nothing to clear.")
else:
    object_names = [item.object_name for item in client.list_objects(bucket, recursive=True)]
    errors = list(client.remove_objects(bucket, (DeleteObject(name) for name in object_names)))
    if errors:
        raise RuntimeError("; ".join(f"{error.object_name}: {error.message}" for error in errors))
    print(f"Cleared {len(object_names)} objects from MinIO bucket {bucket}.")
