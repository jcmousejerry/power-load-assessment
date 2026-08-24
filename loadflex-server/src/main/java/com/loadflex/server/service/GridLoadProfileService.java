package com.loadflex.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GridLoadProfileService {
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final String endpoint;
    private final String authorization;

    public GridLoadProfileService(
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Value("${loadflex.grid.clickhouse-http-endpoint:http://192.168.167.134:8123}") String endpoint,
            @Value("${loadflex.grid.clickhouse-username:loadflex}") String username,
            @Value("${loadflex.grid.clickhouse-password:123456}") String password) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.authorization = "Basic "
                + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public ProfileSet load(
            List<String> transformerCodes, LocalDateTime startTime, String profileType, LocalDate historicalDate) {
        if (transformerCodes.isEmpty()) {
            return new ProfileSet("EMPTY", Map.of());
        }
        try {
            Map<String, Map<Integer, Double>> clickhouse =
                    queryClickHouse(transformerCodes, startTime.toLocalDate(), profileType, historicalDate);
            if (!clickhouse.isEmpty()) {
                return new ProfileSet("CLICKHOUSE", fillMissing(transformerCodes, clickhouse));
            }
        } catch (Exception ignored) {
            // A visible fallback label is returned to the user. Credentials and raw errors are never logged.
        }
        return new ProfileSet("LIVE_FALLBACK", fallback(transformerCodes));
    }

    private Map<String, Map<Integer, Double>> queryClickHouse(
            List<String> codes, LocalDate targetDate, String profileType, LocalDate historicalDate) throws Exception {
        String codeList =
                codes.stream().map(code -> "'" + code.replace("'", "''") + "'").collect(Collectors.joining(","));
        String sql;
        if ("HISTORICAL_DAY".equals(profileType) && historicalDate != null) {
            String source = "(SELECT transformer_id, toStartOfFifteenMinutes(event_time) sample_time, "
                    + "sum(load_kw) total_load_kw FROM loadflex_grid.transformer_history "
                    + "GROUP BY transformer_id, sample_time UNION ALL "
                    + "SELECT transformer_id, toStartOfFifteenMinutes(event_time) sample_time, "
                    + "sum(load_kw) total_load_kw FROM loadflex_grid.transformer_realtime_telemetry FINAL "
                    + "WHERE event_time >= now() - INTERVAL 7 DAY GROUP BY transformer_id, sample_time)";
            sql = "SELECT transformer_id, toHour(sample_time) * 4 + intDiv(toMinute(sample_time), 15) slot, "
                    + "avg(total_load_kw) load_kw FROM " + source + " samples WHERE transformer_id IN ("
                    + codeList + ") AND toDate(sample_time) = toDate('" + historicalDate
                    + "') GROUP BY transformer_id, slot ORDER BY transformer_id, slot FORMAT JSONEachRow";
        } else {
            boolean weekend =
                    targetDate.getDayOfWeek() == DayOfWeek.SATURDAY || targetDate.getDayOfWeek() == DayOfWeek.SUNDAY;
            String dayType = weekend ? "WEEKEND" : "WORKDAY";
            String valueColumn = "P50".equals(profileType) ? "p50_load_kw" : "p90_load_kw";
            sql = "SELECT transformer_id, slot_of_day slot, argMax(" + valueColumn
                    + ", calculated_at) load_kw FROM loadflex_grid.transformer_load_profile_15m "
                    + "WHERE transformer_id IN (" + codeList + ") AND day_type='" + dayType
                    + "' GROUP BY transformer_id, slot_of_day ORDER BY transformer_id, slot FORMAT JSONEachRow";
        }
        URI uri = URI.create(endpoint + "/?query=" + URLEncoder.encode(sql, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", authorization)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("ClickHouse query failed");
        }
        Map<String, Map<Integer, Double>> result = new LinkedHashMap<>();
        for (String line : response.body().split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = objectMapper.readTree(line);
            result.computeIfAbsent(row.path("transformer_id").asText(), ignored -> new HashMap<>())
                    .put(row.path("slot").asInt(), row.path("load_kw").asDouble());
        }
        return result;
    }

    private Map<String, Map<Integer, Double>> fallback(List<String> codes) {
        Map<String, BigDecimal> current = new HashMap<>();
        if (!codes.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(codes.size(), "?"));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT transformer_code, current_load_kw FROM grid_transformer_metric WHERE transformer_code IN ("
                            + placeholders + ")",
                    codes.toArray());
            rows.forEach(row ->
                    current.put(String.valueOf(row.get("transformer_code")), (BigDecimal) row.get("current_load_kw")));
        }
        Map<String, Map<Integer, Double>> result = new LinkedHashMap<>();
        for (String code : codes) {
            double base = current.getOrDefault(code, BigDecimal.valueOf(defaultLoad(code)))
                    .doubleValue();
            Map<Integer, Double> slots = new LinkedHashMap<>();
            for (int slot = 0; slot < 96; slot++) {
                double hour = slot / 4.0;
                double factor =
                        0.72 + 0.20 * Math.sin((hour - 7.0) * Math.PI / 12.0) + (hour >= 9 && hour <= 18 ? 0.16 : 0.0);
                slots.put(slot, Math.max(20.0, Math.round(base * factor * 1000.0) / 1000.0));
            }
            result.put(code, slots);
        }
        return result;
    }

    private double defaultLoad(String code) {
        int number;
        try {
            number = Integer.parseInt(code.replaceAll("\\D", ""));
        } catch (NumberFormatException exception) {
            number = 1;
        }
        return 260.0 + (number % 5) * 75.0;
    }

    private Map<String, Map<Integer, Double>> fillMissing(
            List<String> codes, Map<String, Map<Integer, Double>> source) {
        Map<String, Map<Integer, Double>> result = new LinkedHashMap<>();
        Map<String, Map<Integer, Double>> fallback = fallback(new ArrayList<>(codes));
        for (String code : codes) {
            Map<Integer, Double> slots = new LinkedHashMap<>();
            Map<Integer, Double> available = source.getOrDefault(code, Map.of());
            for (int slot = 0; slot < 96; slot++) {
                slots.put(slot, available.getOrDefault(slot, fallback.get(code).get(slot)));
            }
            result.put(code, slots);
        }
        return result;
    }

    public record ProfileSet(String dataSource, Map<String, Map<Integer, Double>> values) {
        public double value(String transformerCode, LocalDateTime time) {
            int slot = time.getHour() * 4 + time.getMinute() / 15;
            return values.getOrDefault(transformerCode, Map.of()).getOrDefault(slot, 0.0);
        }
    }
}
