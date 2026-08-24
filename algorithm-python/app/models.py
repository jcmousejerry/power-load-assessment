from typing import Any, Dict, Optional

from pydantic import BaseModel, ConfigDict, Field


class AlgorithmRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    task_id: int = Field(alias="taskId")
    dataset_id: int = Field(alias="datasetId")
    task_type: str = Field(alias="taskType")
    object_key: str = Field(alias="objectKey")
    mapping_json: Optional[str] = Field(default=None, alias="mappingJson")
    parameters_json: Optional[str] = Field(default=None, alias="parametersJson")
    upstream_results_json: Optional[str] = Field(default=None, alias="upstreamResultsJson")
    algorithm_version: str = Field(default="1.0.0", alias="algorithmVersion")

class AlgorithmResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    success: bool
    message: str = ""
    summary: Dict[str, Any] = Field(default_factory=dict)
    artifact_object_key: Optional[str] = Field(default=None, alias="artifactObjectKey")
    artifact_sha256: Optional[str] = Field(default=None, alias="artifactSha256")
