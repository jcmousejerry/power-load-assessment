import logging
import os
import time
from typing import Dict

from fastapi import FastAPI

from app.algorithms import execute_algorithm, filter_cluster_data
from app.data_loader import normalize_load_data, parse_json, read_source
from app.models import AlgorithmRequest, AlgorithmResponse
from app.storage import ObjectStorage


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("loadflex-algorithm")

DEMO_DELAY_SECONDS = {
    "PROFILE": 3.0,
    "FEATURE": 4.0,
    "CLUSTER": 5.0,
    "FORECAST": 6.0,
    "BASELINE": 4.0,
    "POTENTIAL": 4.0,
}

app = FastAPI(
    title="LoadFlex Algorithm Service",
    version="1.0.0",
)


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "UP"}


@app.post("/api/v1/execute", response_model=AlgorithmResponse, response_model_by_alias=True)
def execute(request: AlgorithmRequest) -> AlgorithmResponse:
    logger.info(
        "开始算法任务 task_id=%s task_type=%s dataset_id=%s",
        request.task_id,
        request.task_type,
        request.dataset_id,
    )
    try:
        delay_multiplier = max(0.0, float(os.getenv("ALGORITHM_DEMO_DELAY_MULTIPLIER", "1")))
        delay_seconds = DEMO_DELAY_SECONDS.get(request.task_type.upper(), 3.0) * delay_multiplier
        if delay_seconds:
            logger.info("演示执行耗时 task_id=%s delay_seconds=%.1f", request.task_id, delay_seconds)
            time.sleep(delay_seconds)
        storage = ObjectStorage()
        source_path = storage.download(request.object_key, request.task_id)
        source = read_source(source_path)
        mapping = parse_json(request.mapping_json)
        parameters = parse_json(request.parameters_json)
        upstream_results = parse_json(request.upstream_results_json)
        parameters["_upstreamResults"] = upstream_results
        normalized, metadata = normalize_load_data(source, mapping)
        normalized = filter_cluster_data(normalized, request.task_type, parameters, upstream_results)
        summary = execute_algorithm(request.task_type, normalized, metadata, parameters)
        artifact_key, artifact_checksum = storage.upload_result(
            summary,
            request.task_id,
            request.task_type,
        )
        logger.info("算法任务执行成功 task_id=%s", request.task_id)
        return AlgorithmResponse(
            success=True,
            summary=summary,
            artifactObjectKey=artifact_key,
            artifactSha256=artifact_checksum,
        )
    except Exception as exception:
        logger.exception("算法任务执行失败 task_id=%s", request.task_id)
        return AlgorithmResponse(
            success=False,
            message=str(exception),
            summary={},
        )
