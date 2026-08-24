package com.loadflex.consumer.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class AlgorithmResponse {
    private boolean success;
    private String message;
    private JsonNode summary;
    private String artifactObjectKey;
    private String artifactSha256;
}
