package com.loadflex.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BailianClient {
    // 百炼业务空间密钥既可能是传统sk-字符串，也可能是带点分段的JWT样式。
    private static final Pattern API_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9._-]{20,}");
    private static final Pattern BASE_URL_PATTERN = Pattern.compile("https://[^\\s,\\\"]+/compatible-mode/v1");
    private static final Pattern WORKSPACE_URL_PATTERN =
            Pattern.compile("https://([A-Za-z0-9-]+)\\.cn-beijing\\.maas\\.aliyuncs\\.com");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String model;
    private final String configuredApiKey;
    private final String configuredWorkspaceId;
    private final String configuredBaseUrl;
    private final Path projectRoot;

    public BailianClient(
            ObjectMapper objectMapper,
            @Value("${loadflex.bailian.model:qwen3.7-plus-2026-05-26}") String model,
            @Value("${loadflex.bailian.api-key:}") String apiKey,
            @Value("${loadflex.bailian.workspace-id:}") String workspaceId,
            @Value("${loadflex.bailian.base-url:}") String baseUrl,
            @Value("${loadflex.grid.project-root:.}") String projectRoot) {
        this.objectMapper = objectMapper;
        this.model = model;
        this.configuredApiKey = apiKey;
        this.configuredWorkspaceId = workspaceId;
        this.configuredBaseUrl = baseUrl;
        this.projectRoot = Path.of(projectRoot).toAbsolutePath().normalize();
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Completion completeJson(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, true);
    }

    public Completion completeText(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, false);
    }

    public boolean isConfigured() {
        return resolveConfiguration() != null;
    }

    public String modelName() {
        return model;
    }

    public AgentCompletion completeWithTools(
            String systemPrompt, List<Map<String, Object>> history, List<Map<String, Object>> tools) {
        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.addAll(history);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("tools", tools);
            payload.put("tool_choice", "auto");
            payload.put("parallel_tool_calls", false);
            payload.put("temperature", 0.1);
            payload.put("enable_thinking", false);
            JsonNode body = send(payload);
            JsonNode message = body.path("choices").path(0).path("message");
            String content = message.path("content").isNull()
                    ? ""
                    : message.path("content").asText("");
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode item : message.path("tool_calls")) {
                calls.add(new ToolCall(
                        item.path("id").asText(),
                        item.path("function").path("name").asText(),
                        item.path("function").path("arguments").asText("{}")));
            }
            Map<String, Object> assistantMessage = new LinkedHashMap<>();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", content.isBlank() ? null : content);
            if (message.path("tool_calls").isArray()
                    && !message.path("tool_calls").isEmpty()) {
                assistantMessage.put(
                        "tool_calls",
                        objectMapper.convertValue(
                                message.path("tool_calls"), new TypeReference<List<Map<String, Object>>>() {}));
            }
            return new AgentCompletion(
                    content,
                    calls,
                    assistantMessage,
                    body.path("usage").path("prompt_tokens").asInt(0),
                    body.path("usage").path("completion_tokens").asInt(0));
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("百炼工具调用失败：" + exception.getMessage(), exception);
        }
    }

    private Completion complete(String systemPrompt, String userPrompt, boolean jsonMode) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put(
                    "messages",
                    List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)));
            payload.put("temperature", 0.1);
            payload.put("enable_thinking", false);
            if (jsonMode) {
                payload.put("response_format", Map.of("type", "json_object"));
            }
            JsonNode body = send(payload);
            String content =
                    body.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) {
                throw new IllegalStateException("百炼没有返回可用内容");
            }
            return new Completion(
                    content,
                    body.path("usage").path("prompt_tokens").asInt(0),
                    body.path("usage").path("completion_tokens").asInt(0));
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("百炼调用失败：" + exception.getMessage(), exception);
        }
    }

    private JsonNode send(Map<String, Object> payload) {
        Configuration configuration = resolveConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("百炼未配置：请设置DASHSCOPE_API_KEY和BAILIAN_WORKSPACE_ID");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(configuration.baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + configuration.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("百炼调用失败，HTTP状态=" + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百炼调用已取消", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException("百炼调用失败：" + exception.getMessage(), exception);
        }
    }

    private Configuration resolveConfiguration() {
        String apiKey = firstNonBlank(System.getenv("DASHSCOPE_API_KEY"), configuredApiKey);
        String workspaceId = firstNonBlank(System.getenv("BAILIAN_WORKSPACE_ID"), configuredWorkspaceId);
        String baseUrl = firstNonBlank(System.getenv("BAILIAN_BASE_URL"), configuredBaseUrl);
        List<String> localContents = readLocalSecretFiles();
        if (apiKey == null) {
            apiKey = findFirst(localContents, API_KEY_PATTERN, 0);
        }
        if (baseUrl == null) {
            baseUrl = findFirst(localContents, BASE_URL_PATTERN, 0);
        }
        if (workspaceId == null) {
            workspaceId = findFirst(localContents, WORKSPACE_URL_PATTERN, 1);
        }
        if (baseUrl == null && workspaceId != null) {
            baseUrl = "https://" + workspaceId + ".cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
        }
        if (apiKey == null || baseUrl == null) {
            return null;
        }
        return new Configuration(apiKey, baseUrl.replaceAll("/+$", ""));
    }

    private List<String> readLocalSecretFiles() {
        List<String> contents = new ArrayList<>();
        for (String relative : List.of("bailian-docs/api-key相关信息.txt", "bailian-docs/默认业务空间-apiKey-6829892.csv")) {
            Path path = projectRoot.resolve(relative).normalize();
            if (!path.startsWith(projectRoot) || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                contents.add(Files.readString(path, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // Missing local secrets are handled as ordinary unconfigured state.
            }
        }
        return contents;
    }

    private String findFirst(List<String> contents, Pattern pattern, int group) {
        for (String content : contents) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(group);
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    public record Completion(String content, int inputTokens, int outputTokens) {}

    public record AgentCompletion(
            String content,
            List<ToolCall> toolCalls,
            Map<String, Object> assistantMessage,
            int inputTokens,
            int outputTokens) {}

    public record ToolCall(String id, String name, String argumentsJson) {}

    private record Configuration(String apiKey, String baseUrl) {}
}
