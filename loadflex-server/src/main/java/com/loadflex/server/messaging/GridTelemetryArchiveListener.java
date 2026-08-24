package com.loadflex.server.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class GridTelemetryArchiveListener {
    private static final DateTimeFormatter CLICKHOUSE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Shanghai"));
    private static final String INSERT_SQL = "INSERT INTO loadflex_grid.transformer_realtime_telemetry "
            + "(event_time, event_id, transformer_id, meter_id, load_kw, rated_capacity_kw) FORMAT JSONEachRow";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI insertUri;
    private final String authorization;

    public GridTelemetryArchiveListener(
            ObjectMapper objectMapper,
            @Value("${loadflex.grid.clickhouse-http-endpoint:http://192.168.167.134:8123}") String endpoint,
            @Value("${loadflex.grid.clickhouse-username:loadflex}") String username,
            @Value("${loadflex.grid.clickhouse-password:123456}") String password) {
        this.objectMapper = objectMapper;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String query = URLEncoder.encode(INSERT_SQL, StandardCharsets.UTF_8);
        this.insertUri = URI.create(endpoint.replaceAll("/+$", "") + "/?query=" + query);
        this.authorization = "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @KafkaListener(
            topics = "${loadflex.grid.telemetry-topic:grid-telemetry-raw}",
            groupId = "loadflex-grid-telemetry-clickhouse",
            containerFactory = "gridTelemetryArchiveKafkaListenerContainerFactory")
    public void archiveBatch(List<String> payloads) throws IOException, InterruptedException {
        if (payloads.isEmpty()) {
            return;
        }
        List<String> rows = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            JsonNode node = objectMapper.readTree(payload);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("event_time", CLICKHOUSE_TIME.format(Instant.ofEpochMilli(requiredLong(node, "eventTimeEpochMs"))));
            row.put("event_id", requiredText(node, "eventId"));
            row.put("transformer_id", requiredText(node, "transformerId"));
            row.put("meter_id", requiredText(node, "meterId"));
            row.put("load_kw", requiredDouble(node, "loadKw"));
            row.put("rated_capacity_kw", requiredDouble(node, "ratedCapacityKw"));
            rows.add(objectMapper.writeValueAsString(row));
        }
        HttpRequest request = HttpRequest.newBuilder(insertUri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Content-Type", "application/x-ndjson; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(String.join("\n", rows), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("ClickHouse实时遥测批量写入失败，HTTP " + response.statusCode() + "：" + response.body());
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("实时遥测缺少字段：" + field);
        }
        return value;
    }

    private long requiredLong(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new IllegalArgumentException("实时遥测缺少字段：" + field);
        }
        return node.get(field).asLong();
    }

    private double requiredDouble(JsonNode node, String field) {
        if (!node.hasNonNull(field)) {
            throw new IllegalArgumentException("实时遥测缺少字段：" + field);
        }
        return node.get(field).asDouble();
    }
}
