package com.loadflex.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.server.security.AccessControlService;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class PlanningAgentService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern TRANSFORMER_PATTERN = Pattern.compile("(?i)T\\d{3}");
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{2,3}(?:\\.\\d+)?)\\s*%");
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2})(?::|点)(\\d{1,2})?");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BailianClient bailianClient;
    private final MaintenancePlanningService planningService;
    private final GridControlService controlService;
    private final AccessControlService accessControlService;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "grid-planning-agent");
        thread.setDaemon(true);
        return thread;
    });

    public PlanningAgentService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            BailianClient bailianClient,
            MaintenancePlanningService planningService,
            GridControlService controlService,
            AccessControlService accessControlService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.bailianClient = bailianClient;
        this.planningService = planningService;
        this.controlService = controlService;
        this.accessControlService = accessControlService;
    }

    @Transactional
    public Map<String, Object> createSession(String title) {
        long userId = UserContext.get().getUserId();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO planning_agent_session(user_id, title) VALUES (?, ?)", new String[] {"id"});
                    statement.setLong(1, userId);
                    statement.setString(2, title == null || title.isBlank() ? "新的检修方案" : title.trim());
                    return statement;
                },
                holder);
        return sessionDetail(Objects.requireNonNull(holder.getKey()).longValue());
    }

    public List<Map<String, Object>> listSessions() {
        return jdbcTemplate.query(
                "SELECT * FROM planning_agent_session WHERE user_id=? ORDER BY updated_at DESC",
                this::sessionRow,
                UserContext.get().getUserId());
    }

    public Map<String, Object> sessionDetail(long sessionId) {
        Map<String, Object> session = requireSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>(session);
        result.put(
                "messages",
                jdbcTemplate.query(
                        "SELECT * FROM planning_agent_message WHERE session_id=? ORDER BY id",
                        this::messageRow,
                        sessionId));
        List<Map<String, Object>> runs = jdbcTemplate.query(
                "SELECT * FROM planning_agent_run WHERE session_id=? ORDER BY id DESC", this::runRow, sessionId);
        for (Map<String, Object> run : runs) {
            long runId = ((Number) run.get("id")).longValue();
            run.put(
                    "steps",
                    jdbcTemplate.query(
                            "SELECT * FROM planning_agent_step WHERE run_id=? ORDER BY step_no", this::stepRow, runId));
            run.put(
                    "approvals",
                    jdbcTemplate.query(
                            "SELECT * FROM planning_agent_approval WHERE run_id=? ORDER BY id",
                            this::approvalRow,
                            runId));
        }
        result.put("runs", runs);
        result.put(
                "approvals",
                jdbcTemplate.query(
                        "SELECT * FROM planning_agent_approval WHERE session_id=? ORDER BY id DESC",
                        this::approvalRow,
                        sessionId));
        return result;
    }

    @Transactional
    public void deleteSession(long sessionId) {
        requireSession(sessionId);
        Integer activeRuns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM planning_agent_run WHERE session_id=? "
                        + "AND status IN ('RUNNING','CANCEL_REQUESTED')",
                Integer.class,
                sessionId);
        if (activeRuns != null && activeRuns > 0) {
            throw new IllegalArgumentException("Agent仍在工作，请先取消并等待停止后再删除");
        }
        // Messages, runs, steps and approvals are removed by ON DELETE CASCADE. A linked formal
        // maintenance plan is a separately authorised artifact and is deliberately retained.
        jdbcTemplate.update("DELETE FROM planning_agent_session WHERE id=?", sessionId);
    }

    @Transactional
    public Map<String, Object> sendMessage(long sessionId, String content) {
        Map<String, Object> session = requireSession(sessionId);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("请输入检修目标或修改要求");
        }
        LoginUser user = UserContext.get();
        jdbcTemplate.update(
                "INSERT INTO planning_agent_message(session_id, role_code, content_text) VALUES (?, 'USER', ?)",
                sessionId,
                content.trim());
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO planning_agent_run(session_id, user_id, status, current_stage) "
                                    + "VALUES (?, ?, 'RUNNING', '理解检修目标')",
                            new String[] {"id"});
                    statement.setLong(1, sessionId);
                    statement.setLong(2, user.getUserId());
                    return statement;
                },
                holder);
        long runId = Objects.requireNonNull(holder.getKey()).longValue();
        jdbcTemplate.update("UPDATE planning_agent_session SET updated_at=NOW(3) WHERE id=?", sessionId);
        LoginUser captured = new LoginUser(user.getUserId(), user.getUsername(), user.getRoleCode());
        CompletableFuture.runAsync(() -> executeRun(runId, session, content.trim(), captured), executor);
        return runSnapshot(runId, user.getUserId());
    }

    public Map<String, Object> run(long runId) {
        return runSnapshot(runId, UserContext.get().getUserId());
    }

    @Transactional
    public Map<String, Object> cancel(long runId) {
        Map<String, Object> run = runSnapshot(runId, UserContext.get().getUserId());
        if (!Set.of("RUNNING", "WAITING_APPROVAL").contains(String.valueOf(run.get("status")))) {
            return run;
        }
        jdbcTemplate.update(
                "UPDATE planning_agent_run SET status='CANCEL_REQUESTED', current_stage='正在取消' WHERE id=?", runId);
        jdbcTemplate.update(
                "UPDATE planning_agent_approval SET status='CANCELLED', decided_at=NOW(3) "
                        + "WHERE run_id=? AND status='PENDING'",
                runId);
        return runSnapshot(runId, UserContext.get().getUserId());
    }

    public Map<String, Object> decideApproval(long approvalId, boolean approve) {
        LoginUser user = UserContext.get();
        Map<String, Object> approval = requireApproval(approvalId, user.getUserId());
        if (!"PENDING".equals(approval.get("status"))) {
            return approval;
        }
        if (!approve) {
            jdbcTemplate.update(
                    "UPDATE planning_agent_approval SET status='REJECTED', decided_at=NOW(3) WHERE id=?", approvalId);
            jdbcTemplate.update(
                    "UPDATE planning_agent_run SET status='COMPLETED', current_stage='用户拒绝写入', "
                            + "finished_at=NOW(3) WHERE id=?",
                    approval.get("runId"));
            appendAssistant(
                    ((Number) approval.get("sessionId")).longValue(),
                    "已取消写入。临时分析和仿真结果仍保留在本次会话中，你可以继续提出修改要求。",
                    Map.of("approvalId", approvalId, "decision", "REJECTED"));
            return requireApproval(approvalId, user.getUserId());
        }
        accessControlService.requireAnalysisPermission();
        jdbcTemplate.update(
                "UPDATE planning_agent_approval SET status='EXECUTING' WHERE id=? AND status='PENDING'", approvalId);
        jdbcTemplate.update(
                "UPDATE planning_agent_run SET status='RUNNING', current_stage='已获授权，正在执行完整处置任务', "
                        + "finished_at=NULL WHERE id=?",
                approval.get("runId"));
        LoginUser captured = new LoginUser(user.getUserId(), user.getUsername(), user.getRoleCode());
        executor.execute(() -> continueApprovedWorkflow(approvalId, approval, captured));
        return requireApproval(approvalId, user.getUserId());
    }

    private void continueApprovedWorkflow(long approvalId, Map<String, Object> approval, LoginUser user) {
        UserContext.set(user);
        try {
            Map<String, Object> payload = parseMap(String.valueOf(approval.get("payloadJson")));
            String action = String.valueOf(approval.get("actionType"));
            if ("EXECUTE_RESPONSE_WORKFLOW".equals(action) || "CREATE_DRAFT".equals(action)) {
                runAutonomousExecutionLoop(approval, payload);
            } else if ("SUBMIT_PLAN".equals(action)) {
                planningService.submit(
                        ((Number) payload.get("planId")).longValue(),
                        ((Number) payload.get("scenarioId")).longValue(),
                        "由异常处置Agent发起提交");
            } else {
                throw new IllegalArgumentException("不支持的Agent审批动作：" + action);
            }
            jdbcTemplate.update(
                    "UPDATE planning_agent_approval SET status='APPROVED', decided_at=NOW(3) WHERE id=?", approvalId);
            jdbcTemplate.update(
                    "UPDATE planning_agent_run SET status='COMPLETED', current_stage='处置任务已完成', "
                            + "finished_at=NOW(3) WHERE id=?",
                    approval.get("runId"));
        } catch (Exception exception) {
            failRunningTools(((Number) approval.get("runId")).longValue(), exception);
            jdbcTemplate.update(
                    "UPDATE planning_agent_approval SET status='FAILED', decided_at=NOW(3) WHERE id=?", approvalId);
            jdbcTemplate.update(
                    "UPDATE planning_agent_run SET status='FAILED', current_stage='写入失败', error_message=?, "
                            + "finished_at=NOW(3) WHERE id=?",
                    safeError(exception),
                    approval.get("runId"));
            addStep(
                    ((Number) approval.get("runId")).longValue(),
                    "ERROR",
                    null,
                    "FAILED",
                    "执行处置任务失败：" + safeError(exception),
                    null);
        } finally {
            UserContext.clear();
        }
    }

    public void stream(long runId, long userId, SseEmitter emitter) {
        executor.execute(() -> {
            long lastStepId = 0;
            long deadline = System.currentTimeMillis() + 125_000L;
            try {
                while (System.currentTimeMillis() < deadline) {
                    Map<String, Object> snapshot = runSnapshot(runId, userId);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> steps = (List<Map<String, Object>>) snapshot.get("steps");
                    for (Map<String, Object> step : steps) {
                        long stepId = ((Number) step.get("id")).longValue();
                        if (stepId > lastStepId) {
                            emitter.send(SseEmitter.event()
                                    .name("step")
                                    .id(String.valueOf(stepId))
                                    .data(step));
                            lastStepId = stepId;
                        }
                    }
                    emitter.send(SseEmitter.event().name("state").data(snapshot));
                    String status = String.valueOf(snapshot.get("status"));
                    if (!Set.of("RUNNING", "CANCEL_REQUESTED").contains(status)) {
                        emitter.send(SseEmitter.event().name("done").data(snapshot));
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(500);
                }
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
    }

    private void executeRun(long runId, Map<String, Object> session, String content, LoginUser user) {
        UserContext.set(user);
        int[] tokens = new int[] {0, 0};
        try {
            addStep(
                    runId,
                    "PLAN",
                    null,
                    "SUCCEEDED",
                    "Agent将根据每轮新证据自主决定下一项调查工具，并在安全方案出现后请求执行授权",
                    Map.of(
                            "mode",
                            "MODEL_DRIVEN_TOOL_LOOP",
                            "todo",
                            List.of("理解目标", "自主取证", "自主选择分析工具", "判断是否具备安全方案", "请求一次性仿真调控授权")));
            startTool(runId, "understand_task", "正在理解任务边界和可执行参数", Map.of("userRequest", content));
            Extraction extraction = extract(content);
            tokens[0] += extraction.inputTokens();
            tokens[1] += extraction.outputTokens();
            completeRunningTool(
                    runId,
                    "understand_task",
                    "已明确故障设备、分析时间、负荷口径和安全线",
                    Map.of(
                            "request",
                            extraction.toMap(),
                            "verification",
                            Map.of("complete", missingFields(extraction).isEmpty())));
            List<String> missing = missingFields(extraction);
            if (!missing.isEmpty()) {
                String question = "还需要你补充" + String.join("、", missing) + "，补充后Agent才能继续自主调查。";
                appendAssistant(((Number) session.get("id")).longValue(), question, Map.of("missing", missing));
                addStep(runId, "RESULT", null, "COMPLETED", "等待用户补充必要信息", Map.of("missing", missing));
                finish(runId, "COMPLETED", "等待补充信息", tokens[0], tokens[1], null);
                return;
            }
            if ("SUBMIT_PLAN".equals(extraction.intent()) && session.get("linkedPlanId") != null) {
                prepareSubmitApproval(runId, session);
                return;
            }
            runInvestigationLoop(runId, session, content, extraction, tokens);
        } catch (Exception exception) {
            failRunningTools(runId, exception);
            addStep(runId, "ERROR", null, "FAILED", "Agent运行失败：" + safeError(exception), null);
            finish(runId, "FAILED", "运行失败", tokens[0], tokens[1], safeError(exception));
        } finally {
            UserContext.clear();
        }
    }

    private void runInvestigationLoop(
            long runId, Map<String, Object> session, String userRequest, Extraction extraction, int[] tokens) {
        long sessionId = ((Number) session.get("id")).longValue();
        InvestigationState state = new InvestigationState(extraction);
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", userRequest + "\n系统已确认的执行参数：" + writeJson(extraction.toMap())));
        Set<String> signatures = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> tools = investigationTools();
        for (int turn = 1; turn <= 10; turn++) {
            if (isCancelled(runId)) {
                finishCancelled(runId);
                return;
            }
            updateStage(runId, "Agent正在进行第" + turn + "轮自主判断");
            BailianClient.AgentCompletion completion =
                    bailianClient.completeWithTools(investigationSystemPrompt(), history, tools);
            tokens[0] += completion.inputTokens();
            tokens[1] += completion.outputTokens();
            history.add(completion.assistantMessage());
            String selected = completion.toolCalls().isEmpty()
                    ? "形成阶段结论"
                    : completion.toolCalls().stream()
                            .map(BailianClient.ToolCall::name)
                            .collect(Collectors.joining("、"));
            addStep(
                    runId,
                    "DECISION",
                    null,
                    "SUCCEEDED",
                    "模型第" + turn + "轮自主决定：" + selected,
                    Map.of(
                            "action", "模型根据用户目标和此前工具观察选择下一步",
                            "input", Map.of("round", turn, "observedTools", new ArrayList<>(state.completedTools)),
                            "output",
                                    Map.of(
                                            "decisionSummary", completion.content(),
                                            "selectedTools",
                                                    completion.toolCalls().stream()
                                                            .map(BailianClient.ToolCall::name)
                                                            .toList()),
                            "verification", Map.of("nativeFunctionCalling", true, "model", bailianClient.modelName()),
                            "durationMs", 0));
            if (completion.toolCalls().isEmpty()) {
                List<String> missingTools = state.missingEvidenceTools();
                if (!missingTools.isEmpty()) {
                    history.add(Map.of(
                            "role", "user", "content", "调查尚未完成。必须先调用这些工具取得事实证据：" + String.join("、", missingTools)));
                    addStep(
                            runId,
                            "GUARDRAIL",
                            null,
                            "SUCCEEDED",
                            "系统发现证据不足，要求模型继续调查",
                            Map.of("missingTools", missingTools));
                    continue;
                }
                if (state.hasFeasibleRecommendation() && !state.approvalCreated) {
                    history.add(Map.of(
                            "role", "user", "content", "已经找到安全候选方案。请调用request_execution_approval申请一次性仿真调控授权，不要直接结束。"));
                    continue;
                }
                String finalText = completion.content().isBlank()
                        ? deterministicSummary(extraction, state.preview, state.recommended)
                        : completion.content();
                appendAssistant(
                        sessionId,
                        finalText,
                        Map.of("agentMode", "MODEL_DRIVEN_TOOL_LOOP", "modelConfigured", bailianClient.isConfigured()));
                saveInvestigationDraft(sessionId, state);
                addStep(
                        runId,
                        "RESULT",
                        null,
                        "SUCCEEDED",
                        state.approvalCreated ? "自主调查完成，已申请仿真调控授权" : "自主调查完成，当前没有安全可执行路径",
                        state.recommended);
                finish(
                        runId,
                        state.approvalCreated ? "WAITING_APPROVAL" : "COMPLETED",
                        state.approvalCreated ? "等待用户授权自主仿真调控" : "自主诊断完成",
                        tokens[0],
                        tokens[1],
                        null);
                return;
            }
            for (BailianClient.ToolCall call : completion.toolCalls()) {
                Map<String, Object> arguments = parseMap(call.argumentsJson());
                String signature = call.name() + ":" + writeJson(arguments) + ":" + state.completedTools;
                Map<String, Object> observation;
                if (!signatures.add(signature)) {
                    observation = Map.of("ok", false, "error", "相同工具和参数已经执行过，请利用已有结果选择不同的下一步");
                    addStep(
                            runId,
                            "GUARDRAIL",
                            null,
                            "SUCCEEDED",
                            "阻止模型重复执行完全相同的工具调用",
                            Map.of("toolName", call.name(), "arguments", arguments));
                } else {
                    observation = executeInvestigationTool(runId, sessionId, call, arguments, state);
                }
                history.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", writeJson(observation)));
            }
        }
        throw new IllegalStateException("Agent已达到最大自主决策轮次，未能形成可靠结论");
    }

    private Map<String, Object> executeInvestigationTool(
            long runId,
            long sessionId,
            BailianClient.ToolCall call,
            Map<String, Object> arguments,
            InvestigationState state) {
        String name = call.name();
        startTool(
                runId, name, "模型自主调用：" + toolPlainName(name), Map.of("modelCallId", call.id(), "arguments", arguments));
        try {
            Object data;
            switch (name) {
                case "inspect_live_anomaly" -> {
                    data = collectLiveEvidence(state.extraction.transformerCode());
                    state.liveEvidence = castMap(data);
                }
                case "inspect_planning_topology" -> {
                    data = planningService.agentTopologyContext(state.extraction.transformerCode());
                    state.topologyEvidence = castMap(data);
                }
                case "evaluate_transfer_options" -> {
                    state.preview = planningService.agentDraftPreview(
                            state.extraction.transformerCode(),
                            state.extraction.startTime(),
                            state.extraction.endTime(),
                            state.extraction.safetyLimit(),
                            state.extraction.profileType());
                    state.recommended = castMap(state.preview.get("recommended"));
                    data = state.preview;
                    saveInvestigationDraft(sessionId, state);
                }
                case "request_execution_approval" -> {
                    if (!state.missingEvidenceTools().isEmpty()) {
                        throw new IllegalArgumentException("证据尚未收集完整，不能申请执行授权");
                    }
                    if (!state.hasFeasibleRecommendation()) {
                        throw new IllegalArgumentException("当前没有通过安全线的方案，不能申请执行授权");
                    }
                    createDraftApproval(
                            runId,
                            sessionId,
                            UserContext.get().getUserId(),
                            state.extraction,
                            state.preview,
                            state.recommended);
                    state.approvalCreated = true;
                    data = Map.of(
                            "approvalCreated",
                            true,
                            "recommended",
                            state.recommended,
                            "next",
                            "等待用户批准后，Agent将继续自主完成正式仿真和五步仿真调控");
                }
                default -> throw new IllegalArgumentException("模型选择了未开放的调查工具：" + name);
            }
            state.completedTools.add(name);
            Object observationData = compactInvestigationObservation(name, data);
            completeRunningTool(
                    runId,
                    name,
                    toolPlainName(name) + "已执行并取得观察结果",
                    Map.of(
                            "data",
                            observationData,
                            "verification",
                            Map.of("resultPresent", data != null, "executedByBackend", true)));
            return Map.of("ok", true, "tool", name, "data", observationData);
        } catch (Exception exception) {
            failRunningTool(runId, name, exception);
            return Map.of("ok", false, "tool", name, "error", safeError(exception));
        }
    }

    private void saveInvestigationDraft(long sessionId, InvestigationState state) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("request", state.extraction.toMap());
        draft.put("liveEvidence", state.liveEvidence);
        draft.put("topologyEvidence", state.topologyEvidence);
        draft.put("preview", state.preview);
        draft.put("agentMode", "MODEL_DRIVEN_TOOL_LOOP");
        jdbcTemplate.update(
                "UPDATE planning_agent_session SET draft_json=?, updated_at=NOW(3) WHERE id=?",
                writeJson(draft),
                sessionId);
    }

    private Object compactInvestigationObservation(String toolName, Object data) {
        if (!(data instanceof Map<?, ?>)) {
            return data;
        }
        Map<String, Object> source = castMap(data);
        if ("evaluate_transfer_options".equals(toolName)) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : List.of(
                    "transformerCode",
                    "sourceFeederCode",
                    "startTime",
                    "endTime",
                    "safetyLimit",
                    "profileType",
                    "dataSource")) {
                result.put(key, source.get(key));
            }
            result.put("candidates", compactScenarios(source.get("candidates")));
            result.put("recommended", compactScenario(source.get("recommended")));
            return result;
        }
        if ("request_execution_approval".equals(toolName)) {
            return Map.of(
                    "approvalCreated", true,
                    "recommended", compactScenario(source.get("recommended")),
                    "next", source.get("next"));
        }
        return data;
    }

    private String investigationSystemPrompt() {
        return "你是自主电网故障仿真处置Agent。你必须根据每一轮真实工具结果自己选择下一项工具，"
                + "而不是预先输出固定流程。一次只调用一个工具。先取得实时异常证据、拓扑证据和转供计算结果。"
                + "不得编造设备、路径或指标。若存在安全候选，必须调用request_execution_approval；若不存在安全候选，"
                + "在证据完整后直接输出Markdown结论。不要输出隐藏思维链，只用一句话说明当前选择的目的。"
                + "所有调控仅限系统仿真，绝不能声称控制了真实电网设备。";
    }

    private List<Map<String, Object>> investigationTools() {
        return List.of(
                functionTool(
                        "inspect_live_anomaly",
                        "查询指定变压器的实时负载、最近一小时趋势和最近预警。需要核实故障是否真实存在时调用。",
                        Map.of("transformerCode", stringProperty("变压器编号，例如T001")),
                        List.of("transformerCode")),
                functionTool(
                        "inspect_planning_topology",
                        "查询故障变压器所属馈线、相邻馈线、联络开关和可接收负荷的设备。需要理解供电连接时调用。",
                        Map.of("transformerCode", stringProperty("变压器编号，例如T001")),
                        List.of("transformerCode")),
                functionTool(
                        "evaluate_transfer_options",
                        "读取负荷画像并逐时计算全部备用供电路径，返回每条路径的最高负载率、风险时间和安全结论。",
                        Map.of(
                                "transformerCode", stringProperty("故障变压器编号"),
                                "startTime", stringProperty("ISO格式开始时间"),
                                "endTime", stringProperty("ISO格式结束时间"),
                                "safetyLimit", numberProperty("0到1之间的容量安全线"),
                                "profileType", stringProperty("P90、MAX或HISTORICAL_DAY")),
                        List.of("transformerCode", "startTime", "endTime", "safetyLimit", "profileType")),
                functionTool(
                        "request_execution_approval",
                        "当且仅当证据完整且至少有一条路径通过安全线时，申请一次性自主仿真调控授权。",
                        Map.of("reason", stringProperty("为什么建议执行该安全方案")),
                        List.of("reason")));
    }

    private Map<String, Object> functionTool(
            String name, String description, Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type",
                "function",
                "function",
                Map.of(
                        "name", name,
                        "description", description,
                        "parameters",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        properties,
                                        "required",
                                        required,
                                        "additionalProperties",
                                        false)));
    }

    private Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private Map<String, Object> numberProperty(String description) {
        return Map.of("type", "number", "description", description);
    }

    private String toolPlainName(String name) {
        return switch (name) {
            case "inspect_live_anomaly" -> "核实实时异常、趋势和预警";
            case "inspect_planning_topology" -> "调查供电拓扑和备用连接";
            case "evaluate_transfer_options" -> "逐时计算全部备用供电路径";
            case "request_execution_approval" -> "申请一次性自主仿真调控授权";
            case "create_response_case" -> "创建正式异常处置任务";
            case "build_formal_scenarios" -> "建立正式候选仿真方案";
            case "run_formal_simulations" -> "运行全部正式逐时仿真";
            case "select_lowest_risk_scenario" -> "选择最低风险安全方案";
            case "create_autonomous_control" -> "创建自主仿真调控会话";
            case "execute_simulated_control_action" -> "执行下一项仿真调控动作";
            case "inspect_control_state" -> "核验仿真调控状态";
            default -> name;
        };
    }

    private void executeLegacyRun(long runId, Map<String, Object> session, String content, LoginUser user) {
        UserContext.set(user);
        int inputTokens = 0;
        int outputTokens = 0;
        try {
            addStep(
                    runId,
                    "PLAN",
                    null,
                    "RUNNING",
                    "已建立任务清单：核实异常 → 读取历史 → 追踪拓扑 → 试算路径 → 交叉核验 → 请求执行授权",
                    Map.of(
                            "todo",
                            List.of(
                                    "核实设备实时状态和最近预警",
                                    "读取最近一小时趋势作为证据",
                                    "追踪上游线路和备用连接",
                                    "生成并试算全部备用路径",
                                    "核验容量、供电连续性和操作顺序",
                                    "经用户授权后创建正式仿真并准备调控台")));
            startTool(runId, "understand_task", "正在理解任务目标并提取设备、时间、负荷口径和安全线", Map.of("userRequest", content));
            Extraction extraction = extract(content);
            completeRunningTool(
                    runId,
                    "understand_task",
                    "已提取任务执行所需参数",
                    Map.of(
                            "request",
                            extraction.toMap(),
                            "requiredFieldsComplete",
                            missingFields(extraction).isEmpty()));
            inputTokens += extraction.inputTokens();
            outputTokens += extraction.outputTokens();
            jdbcTemplate.update(
                    "UPDATE planning_agent_step SET status='SUCCEEDED' WHERE run_id=? AND step_type='PLAN' "
                            + "AND status='RUNNING'",
                    runId);
            if (isCancelled(runId)) {
                finishCancelled(runId);
                return;
            }
            if ("SUBMIT_PLAN".equals(extraction.intent()) && session.get("linkedPlanId") != null) {
                prepareSubmitApproval(runId, session);
                return;
            }
            List<String> missing = missingFields(extraction);
            if (!missing.isEmpty()) {
                String question = "还需要你补充" + String.join("、", missing) + "，补充后我再生成并校核转供方案。";
                appendAssistant(((Number) session.get("id")).longValue(), question, Map.of("missing", missing));
                addStep(runId, "RESULT", null, "COMPLETED", "等待用户补充必要信息", Map.of("missing", missing));
                finish(runId, "COMPLETED", "等待补充信息", inputTokens, outputTokens, null);
                return;
            }
            updateStage(runId, "查询设备档案与拓扑");
            startTool(
                    runId,
                    "inspect_live_anomaly",
                    "正在查询实时指标、最近一小时趋势和最新预警",
                    Map.of("transformerCode", extraction.transformerCode(), "trendWindowMinutes", 60));
            Map<String, Object> liveEvidence = collectLiveEvidence(extraction.transformerCode());
            completeRunningTool(
                    runId,
                    "inspect_live_anomaly",
                    "已核实" + extraction.transformerCode() + "的实时状态、最近预警和一小时趋势",
                    Map.of(
                            "evidence",
                            liveEvidence,
                            "verification",
                            Map.of(
                                    "transformerMatched",
                                    extraction.transformerCode(),
                                    "metricPresent",
                                    liveEvidence.get("currentMetric") != null)));
            updateStage(runId, "计算停电影响并运行候选方案仿真");
            startTool(
                    runId,
                    "evaluate_transfer_options",
                    "正在读取负荷曲线并逐点计算全部相邻备用路径",
                    Map.of(
                            "transformerCode", extraction.transformerCode(),
                            "startTime", extraction.startTime(),
                            "endTime", extraction.endTime(),
                            "safetyLimit", extraction.safetyLimit(),
                            "profileType", extraction.profileType()));
            Map<String, Object> preview = planningService.agentDraftPreview(
                    extraction.transformerCode(),
                    extraction.startTime(),
                    extraction.endTime(),
                    extraction.safetyLimit(),
                    extraction.profileType());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) preview.get("candidates");
            completeRunningTool(
                    runId,
                    "evaluate_transfer_options",
                    "已完成" + candidates.size() + "条备用路径的逐时负载计算",
                    Map.of(
                            "dataSource", preview.get("dataSource"),
                            "sourceFeederCode", preview.get("sourceFeederCode"),
                            "candidates", candidates,
                            "verification",
                                    Map.of(
                                            "candidateCount", candidates.size(),
                                            "allCandidatesHaveLoadResult",
                                                    candidates.stream()
                                                            .allMatch(item -> item.get("maximumLoadRate") != null))));
            addStep(
                    runId,
                    "EVIDENCE",
                    null,
                    "SUCCEEDED",
                    "已计算停运设备的转供负荷和数据口径",
                    Map.of("dataSource", preview.get("dataSource"), "sourceFeeder", preview.get("sourceFeederCode")));
            addStep(
                    runId,
                    "EVIDENCE",
                    null,
                    "SUCCEEDED",
                    "已生成" + candidates.size() + "个候选转供路径",
                    Map.of("candidateCount", candidates.size()));
            for (int index = 0; index < candidates.size(); index++) {
                Map<String, Object> candidate = candidates.get(index);
                addStep(
                        runId,
                        "EVIDENCE",
                        null,
                        "SUCCEEDED",
                        "候选" + (index + 1) + "仿真完成：最大负载率" + candidate.get("maximumLoadPercent") + "%",
                        candidate);
            }
            Map<String, Object> recommended = castMap(preview.get("recommended"));
            addStep(
                    runId,
                    "EVIDENCE",
                    null,
                    "SUCCEEDED",
                    "已交叉核验容量安全线、路径完整性、开关顺序和供电恢复结果",
                    Map.of(
                            "candidateCount",
                            candidates.size(),
                            "recommendedFeasible",
                            Boolean.TRUE.equals(recommended.get("feasible")),
                            "checks",
                            List.of("容量不越线", "路径可达", "先隔离后接通", "调控后供电可恢复")));
            String finalText;
            startTool(
                    runId,
                    "explain_findings",
                    "正在依据已取得的证据生成普通用户可读的说明",
                    Map.of("evidenceOnly", true, "modelConfigured", bailianClient.isConfigured()));
            try {
                BailianClient.Completion completion = bailianClient.completeText(
                        "你是面向普通用户的设备异常处理助手。请只依据工具结果，用不需要电网专业知识的中文，"
                                + "按“发生了什么、可能影响哪里、建议怎么做、需要注意什么”四部分说明。首次出现馈线或联络开关时"
                                + "必须顺带解释含义。不得编造设备和指标，不得声称已经控制真实设备。",
                        "用户要求：" + content + "\n实时证据JSON：" + writeJson(liveEvidence) + "\n路径试算JSON："
                                + writeJson(preview));
                finalText = completion.content();
                inputTokens += completion.inputTokens();
                outputTokens += completion.outputTokens();
                completeRunningTool(
                        runId,
                        "explain_findings",
                        "已由大模型依据工具证据生成说明",
                        Map.of(
                                "provider", "BAILIAN",
                                "inputTokens", completion.inputTokens(),
                                "outputTokens", completion.outputTokens(),
                                "outputCharacters", finalText.length(),
                                "verification", Map.of("notBlank", !finalText.isBlank())));
            } catch (Exception exception) {
                finalText = deterministicSummary(extraction, preview, recommended);
                completeRunningTool(
                        runId,
                        "explain_findings",
                        "大模型暂不可用，已使用确定性证据摘要完成说明",
                        Map.of(
                                "provider", "DETERMINISTIC_FALLBACK",
                                "fallbackReason", safeError(exception),
                                "outputCharacters", finalText.length(),
                                "verification", Map.of("notBlank", !finalText.isBlank())));
            }
            long sessionId = ((Number) session.get("id")).longValue();
            appendAssistant(
                    sessionId, finalText, Map.of("preview", preview, "modelConfigured", bailianClient.isConfigured()));
            jdbcTemplate.update(
                    "UPDATE planning_agent_session SET draft_json=?, updated_at=NOW(3) WHERE id=?",
                    writeJson(Map.of("request", extraction.toMap(), "liveEvidence", liveEvidence, "preview", preview)),
                    sessionId);
            addStep(
                    runId,
                    "RESULT",
                    null,
                    "SUCCEEDED",
                    Boolean.TRUE.equals(recommended.get("feasible")) ? "已形成通过临时容量校核的推荐方案" : "当前候选方案均存在容量风险，已给出调整建议",
                    recommended);
            if (!"VIEWER".equals(user.getRoleCode()) && Boolean.TRUE.equals(recommended.get("feasible"))) {
                createDraftApproval(runId, sessionId, user.getUserId(), extraction, preview, recommended);
                finish(runId, "WAITING_APPROVAL", "等待用户授权执行完整处置任务", inputTokens, outputTokens, null);
            } else {
                finish(runId, "COMPLETED", "分析完成", inputTokens, outputTokens, null);
            }
        } catch (Exception exception) {
            failRunningTools(runId, exception);
            addStep(runId, "ERROR", null, "FAILED", "Agent运行失败：" + safeError(exception), null);
            finish(runId, "FAILED", "运行失败", inputTokens, outputTokens, safeError(exception));
        } finally {
            UserContext.clear();
        }
    }

    private Extraction extract(String content) {
        try {
            String now = LocalDateTime.now(ZONE).toString();
            BailianClient.Completion completion = bailianClient.completeJson(
                    "你是电网检修请求解析器。当前时间是" + now + "。只输出JSON，字段为intent、transformerCode、"
                            + "startTime、endTime、safetyLimit、profileType。intent取CREATE_PLAN或SUBMIT_PLAN；"
                            + "时间输出ISO-8601本地时间；safetyLimit输出0到1的小数；profileType取P50或P90。"
                            + "用户未提供的字段输出null，不要猜测。",
                    content);
            Map<String, Object> values = parseMap(stripCodeFence(completion.content()));
            return new Extraction(
                    string(values.get("intent"), "CREATE_PLAN"),
                    upperOrNull(values.get("transformerCode")),
                    parseDateTime(values.get("startTime")),
                    parseDateTime(values.get("endTime")),
                    decimalOrNull(values.get("safetyLimit")),
                    string(values.get("profileType"), "P90"),
                    completion.inputTokens(),
                    completion.outputTokens());
        } catch (Exception exception) {
            return fallbackExtraction(content);
        }
    }

    private Extraction fallbackExtraction(String content) {
        Matcher transformer = TRANSFORMER_PATTERN.matcher(content);
        String transformerCode = transformer.find() ? transformer.group().toUpperCase() : null;
        Matcher percent = PERCENT_PATTERN.matcher(content);
        BigDecimal safety = percent.find() ? new BigDecimal(percent.group(1)).divide(BigDecimal.valueOf(100)) : null;
        LocalDate date = resolveDate(content);
        List<LocalTime> times = new ArrayList<>();
        Matcher timeMatcher = TIME_PATTERN.matcher(content);
        while (timeMatcher.find() && times.size() < 2) {
            int hour = Integer.parseInt(timeMatcher.group(1));
            int minute = timeMatcher.group(2) == null ? 0 : Integer.parseInt(timeMatcher.group(2));
            if (hour <= 23 && minute <= 59) {
                times.add(LocalTime.of(hour, minute));
            }
        }
        LocalDateTime start = date != null && !times.isEmpty() ? LocalDateTime.of(date, times.get(0)) : null;
        LocalDateTime end = date != null && times.size() > 1 ? LocalDateTime.of(date, times.get(1)) : null;
        String intent = content.contains("提交") ? "SUBMIT_PLAN" : "CREATE_PLAN";
        String profile = content.toUpperCase().contains("P50") || content.contains("典型") ? "P50" : "P90";
        return new Extraction(intent, transformerCode, start, end, safety, profile, 0, 0);
    }

    private LocalDate resolveDate(String content) {
        LocalDate today = LocalDate.now(ZONE);
        if (content.contains("明天")) {
            return today.plusDays(1);
        }
        if (content.contains("后天")) {
            return today.plusDays(2);
        }
        if (content.contains("今天")) {
            return today;
        }
        String[] chineseWeekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (int index = 0; index < chineseWeekdays.length; index++) {
            if (content.contains("下周" + chineseWeekdays[index])) {
                DayOfWeek target = DayOfWeek.of(index + 1);
                return today.with(TemporalAdjusters.next(target))
                        .plusWeeks(today.with(TemporalAdjusters.next(target)).isBefore(today.plusDays(7)) ? 1 : 0);
            }
        }
        Matcher isoDate =
                Pattern.compile("(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})").matcher(content);
        if (isoDate.find()) {
            return LocalDate.of(
                    Integer.parseInt(isoDate.group(1)),
                    Integer.parseInt(isoDate.group(2)),
                    Integer.parseInt(isoDate.group(3)));
        }
        return null;
    }

    private List<String> missingFields(Extraction extraction) {
        List<String> missing = new ArrayList<>();
        if (extraction.transformerCode() == null) {
            missing.add("检修变压器编号");
        }
        if (extraction.startTime() == null) {
            missing.add("开始时间");
        }
        if (extraction.endTime() == null) {
            missing.add("结束时间");
        }
        if (extraction.safetyLimit() == null) {
            missing.add("容量安全线");
        }
        return missing;
    }

    private Map<String, Object> collectLiveEvidence(String transformerCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transformerCode", transformerCode);
        List<Map<String, Object>> metrics = jdbcTemplate.query(
                "SELECT current_load_kw, rated_capacity_kw, load_rate, historical_upper_kw, status, window_end "
                        + "FROM grid_transformer_metric WHERE transformer_code=?",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("currentLoadKw", rs.getBigDecimal("current_load_kw"));
                    row.put("ratedCapacityKw", rs.getBigDecimal("rated_capacity_kw"));
                    row.put("loadRate", rs.getBigDecimal("load_rate"));
                    row.put("historicalUpperKw", rs.getBigDecimal("historical_upper_kw"));
                    row.put("status", rs.getString("status"));
                    row.put("windowEnd", rs.getTimestamp("window_end").toLocalDateTime());
                    return row;
                },
                transformerCode);
        result.put("currentMetric", metrics.isEmpty() ? null : metrics.get(0));
        result.put(
                "lastHourTrend",
                jdbcTemplate.query(
                        "SELECT COUNT(*) sample_count, MIN(load_rate) min_rate, AVG(load_rate) avg_rate, "
                                + "MAX(load_rate) max_rate, "
                                + "SUM(CASE WHEN status<>'NORMAL' THEN 1 ELSE 0 END) abnormal_count "
                                + "FROM grid_transformer_metric_history WHERE transformer_code=? "
                                + "AND window_end>=DATE_SUB(NOW(3), INTERVAL 1 HOUR)",
                        rs -> {
                            if (!rs.next()) {
                                return Map.of();
                            }
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("sampleCount", rs.getInt("sample_count"));
                            row.put("minimumLoadRate", rs.getBigDecimal("min_rate"));
                            row.put("averageLoadRate", rs.getBigDecimal("avg_rate"));
                            row.put("maximumLoadRate", rs.getBigDecimal("max_rate"));
                            row.put("abnormalSampleCount", rs.getInt("abnormal_count"));
                            return row;
                        },
                        transformerCode));
        result.put(
                "recentAlerts",
                jdbcTemplate.query(
                        "SELECT alert_type, severity, load_rate, event_time FROM grid_risk_alert "
                                + "WHERE transformer_code=? ORDER BY event_time DESC LIMIT 5",
                        (rs, rowNum) -> Map.of(
                                "alertType", rs.getString("alert_type"),
                                "severity", rs.getString("severity"),
                                "loadRate", rs.getBigDecimal("load_rate"),
                                "eventTime", rs.getTimestamp("event_time").toLocalDateTime()),
                        transformerCode));
        return result;
    }

    private void createDraftApproval(
            long runId,
            long sessionId,
            long userId,
            Extraction extraction,
            Map<String, Object> preview,
            Map<String, Object> recommended) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planName", extraction.transformerCode() + "异常备用处理方案");
        payload.put("reason", "由AI异常处理助手生成的模拟处理方案");
        payload.put("transformerCode", extraction.transformerCode());
        payload.put("startTime", extraction.startTime().toString());
        payload.put("endTime", extraction.endTime().toString());
        payload.put("safetyLimit", extraction.safetyLimit());
        payload.put("profileType", extraction.profileType());
        payload.put("recommended", recommended);
        Map<String, Object> previewCard = new LinkedHashMap<>();
        previewCard.put("title", "准备执行完整异常处置任务");
        previewCard.put("transformerCode", extraction.transformerCode());
        previewCard.put("startTime", extraction.startTime());
        previewCard.put("endTime", extraction.endTime());
        previewCard.put("sourceFeederCode", preview.get("sourceFeederCode"));
        previewCard.put("targetFeederCode", recommended.get("targetFeederCode"));
        previewCard.put("tieSwitchCode", recommended.get("tieSwitchCode"));
        previewCard.put("maximumLoadPercent", recommended.get("maximumLoadPercent"));
        previewCard.put("stepCount", castList(recommended.get("steps")).size());
        jdbcTemplate.update(
                "INSERT INTO planning_agent_approval(run_id, session_id, user_id, action_type, "
                        + "idempotency_key, preview_json, payload_json) "
                        + "VALUES (?, ?, ?, 'EXECUTE_RESPONSE_WORKFLOW', ?, ?, ?)",
                runId,
                sessionId,
                userId,
                UUID.randomUUID().toString(),
                writeJson(previewCard),
                writeJson(payload));
    }

    private void runAutonomousExecutionLoop(Map<String, Object> approval, Map<String, Object> payload) {
        long runId = ((Number) approval.get("runId")).longValue();
        long sessionId = ((Number) approval.get("sessionId")).longValue();
        ExecutionState state = new ExecutionState(payload);
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(Map.of(
                "role",
                "user",
                "content",
                "用户已经批准本次自主仿真调控。调查阶段推荐结果：" + writeJson(payload.get("recommended"))
                        + "。请自主选择工具，完成正式方案、全部仿真、最低风险方案选择，并亲自完成五步仿真调控。"));
        Set<String> signatures = new java.util.LinkedHashSet<>();
        for (int turn = 1; turn <= 18; turn++) {
            if (isCancelled(runId)) {
                throw new IllegalStateException("用户已取消自主仿真调控");
            }
            updateStage(runId, "授权后第" + turn + "轮自主执行：" + state.nextGoal());
            BailianClient.AgentCompletion completion =
                    bailianClient.completeWithTools(executionSystemPrompt(state), history, executionTools());
            accumulateRunTokens(runId, completion.inputTokens(), completion.outputTokens());
            history.add(completion.assistantMessage());
            String selected = completion.toolCalls().isEmpty()
                    ? "汇总自主调控成果"
                    : completion.toolCalls().stream()
                            .map(BailianClient.ToolCall::name)
                            .collect(Collectors.joining("、"));
            addStep(
                    runId,
                    "DECISION",
                    null,
                    "SUCCEEDED",
                    "模型授权后第" + turn + "轮自主决定：" + selected,
                    Map.of(
                            "action", "模型读取上一项工具结果后，自主选择下一项正式仿真或调控动作",
                            "input", Map.of("round", turn, "currentGoal", state.nextGoal()),
                            "output",
                                    Map.of(
                                            "decisionSummary", completion.content(),
                                            "selectedTools",
                                                    completion.toolCalls().stream()
                                                            .map(BailianClient.ToolCall::name)
                                                            .toList()),
                            "verification", Map.of("nativeFunctionCalling", true, "model", bailianClient.modelName()),
                            "durationMs", 0));
            if (completion.toolCalls().isEmpty()) {
                if (!state.controlCompleted()) {
                    history.add(Map.of(
                            "role", "user", "content", "任务尚未完成。当前必须继续：" + state.nextGoal() + "。请调用相应工具，不要提前总结。"));
                    addStep(
                            runId,
                            "GUARDRAIL",
                            null,
                            "SUCCEEDED",
                            "系统阻止Agent在仿真调控完成前提前结束",
                            Map.of("nextGoal", state.nextGoal()));
                    continue;
                }
                String finalText = completion.content().isBlank()
                        ? "自主仿真调控已完成：正式路径仿真通过，系统已经按安全顺序完成隔离、接通备用线路、负荷转移和结果核验。"
                        : completion.content();
                appendAssistant(
                        sessionId,
                        finalText,
                        Map.of(
                                "agentMode", "AUTONOMOUS_SIMULATION_CONTROL",
                                "planId", state.planId,
                                "scenarioId", state.selectedScenarioId,
                                "controlSessionId", state.controlSessionId));
                saveExecutionState(sessionId, state);
                addStep(
                        runId,
                        "RESULT",
                        null,
                        "SUCCEEDED",
                        "Agent已自主完成正式仿真和五步仿真调控",
                        Map.of(
                                "planId", state.planId,
                                "scenarioId", state.selectedScenarioId,
                                "controlSessionId", state.controlSessionId,
                                "controlStatus", state.controlStatus,
                                "verified", state.controlVerified));
                return;
            }
            for (BailianClient.ToolCall call : completion.toolCalls()) {
                Map<String, Object> arguments = parseMap(call.argumentsJson());
                String signature = call.name() + ":" + writeJson(arguments) + ":" + state.nextGoal();
                Map<String, Object> observation;
                if (!signatures.add(signature)) {
                    observation = Map.of("ok", false, "error", "完全相同的调用已经执行过，请检查上一次结果并选择下一项动作");
                    addStep(
                            runId,
                            "GUARDRAIL",
                            null,
                            "SUCCEEDED",
                            "阻止授权后的重复工具调用",
                            Map.of("toolName", call.name(), "arguments", arguments));
                } else {
                    observation = executeAuthorisedTool(runId, sessionId, call, arguments, state);
                }
                history.add(Map.of("role", "tool", "tool_call_id", call.id(), "content", writeJson(observation)));
            }
        }
        throw new IllegalStateException("Agent达到最大自主执行轮次，未能完成仿真调控");
    }

    private Map<String, Object> executeAuthorisedTool(
            long runId,
            long sessionId,
            BailianClient.ToolCall call,
            Map<String, Object> arguments,
            ExecutionState state) {
        String name = call.name();
        startTool(
                runId,
                name,
                "模型自主调用：" + toolPlainName(name),
                Map.of("modelCallId", call.id(), "arguments", arguments, "simulationOnly", true));
        try {
            Object data;
            switch (name) {
                case "create_response_case" -> {
                    if (state.planId != null) {
                        throw new IllegalArgumentException("正式处置任务已经创建");
                    }
                    Map<String, Object> plan = planningService.createPlan(
                            String.valueOf(state.payload.get("planName")),
                            String.valueOf(state.payload.get("reason")),
                            "TRANSFORMER",
                            String.valueOf(state.payload.get("transformerCode")),
                            LocalDateTime.parse(String.valueOf(state.payload.get("startTime"))),
                            LocalDateTime.parse(String.valueOf(state.payload.get("endTime"))),
                            new BigDecimal(String.valueOf(state.payload.get("safetyLimit"))),
                            String.valueOf(state.payload.get("profileType")),
                            null,
                            UserContext.get().getUsername());
                    state.planId = ((Number) plan.get("id")).longValue();
                    jdbcTemplate.update(
                            "UPDATE planning_agent_session SET linked_plan_id=?, updated_at=NOW(3) WHERE id=?",
                            state.planId,
                            sessionId);
                    data = plan;
                }
                case "build_formal_scenarios" -> {
                    state.requirePlan();
                    state.candidates = planningService.generateCandidates(state.planId);
                    Map<String, Object> recommended = castMap(state.payload.get("recommended"));
                    for (Map<String, Object> candidate : state.candidates) {
                        if (Objects.equals(candidate.get("tieSwitchCode"), recommended.get("tieSwitchCode"))) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, String>> steps =
                                    (List<Map<String, String>>) (List<?>) recommended.get("steps");
                            planningService.saveSteps(((Number) candidate.get("id")).longValue(), steps);
                        }
                    }
                    data = Map.of("scenarioCount", state.candidates.size(), "scenarios", state.candidates);
                }
                case "run_formal_simulations" -> {
                    state.requireCandidates();
                    state.taskIds = new ArrayList<>();
                    for (Map<String, Object> candidate : state.candidates) {
                        Map<String, Object> task =
                                planningService.startSimulation(((Number) candidate.get("id")).longValue());
                        state.taskIds.add(((Number) task.get("id")).longValue());
                    }
                    waitForSimulations(runId, state.taskIds);
                    Map<String, Object> completedPlan = planningService.planDetail(state.planId);
                    data = Map.of(
                            "taskIds",
                            state.taskIds,
                            "scenarioResults",
                            compactScenarios(completedPlan.get("scenarios")));
                }
                case "select_lowest_risk_scenario" -> {
                    state.requireSimulations();
                    Map<String, Object> completedPlan = planningService.planDetail(state.planId);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> scenarios = (List<Map<String, Object>>) completedPlan.get("scenarios");
                    Map<String, Object> selected = scenarios.stream()
                            .filter(item -> "SUCCEEDED".equals(item.get("status")))
                            .filter(item -> Boolean.TRUE.equals(
                                    castMap(item.get("summary")).get("feasible")))
                            .min(java.util.Comparator.comparingDouble(item ->
                                    ((Number) castMap(item.get("summary")).get("maximumLoadRate")).doubleValue()))
                            .orElseThrow(() -> new IllegalArgumentException("正式仿真没有找到通过安全校核的路径"));
                    state.selectedScenarioId = ((Number) selected.get("id")).longValue();
                    data = compactScenario(selected);
                }
                case "create_autonomous_control" -> {
                    state.requireSelectedScenario();
                    Map<String, Object> control = controlService.create(
                            state.selectedScenarioId, state.payload.get("transformerCode") + " · Agent自主仿真调控");
                    state.updateControl(control);
                    data = compactControl(control);
                }
                case "execute_simulated_control_action" -> {
                    state.requireControl();
                    String actionType = string(arguments.get("actionType"), "").toUpperCase();
                    Map<String, Object> before = controlService.detail(state.controlSessionId);
                    Map<String, Object> nextAction = castMap(before.get("nextAction"));
                    String expected = String.valueOf(nextAction.get("actionType"));
                    if (!expected.equals(actionType)) {
                        throw new IllegalArgumentException("当前安全顺序要求执行" + expected + "，模型选择了" + actionType);
                    }
                    Map<String, Object> control = controlService.execute(
                            state.controlSessionId,
                            actionType,
                            UUID.randomUUID().toString());
                    state.updateControl(control);
                    Map<String, Object> actionResult = new LinkedHashMap<>();
                    actionResult.put("executedAction", actionType);
                    actionResult.put("before", controlStateSummary(before));
                    actionResult.put("after", controlStateSummary(control));
                    actionResult.put("nextAction", control.get("nextAction"));
                    actionResult.put("effect", control.get("effect"));
                    data = actionResult;
                }
                case "inspect_control_state" -> {
                    state.requireControl();
                    Map<String, Object> control = controlService.detail(state.controlSessionId);
                    state.updateControl(control);
                    data = compactControl(control);
                }
                default -> throw new IllegalArgumentException("模型选择了未开放的授权工具：" + name);
            }
            saveExecutionState(sessionId, state);
            completeRunningTool(
                    runId,
                    name,
                    toolPlainName(name) + "已真实执行并持久化结果",
                    Map.of(
                            "data",
                            data,
                            "verification",
                            Map.of(
                                    "persisted", true,
                                    "simulationOnly", true,
                                    "nextGoal", state.nextGoal())));
            return Map.of("ok", true, "tool", name, "data", data, "nextGoal", state.nextGoal());
        } catch (Exception exception) {
            failRunningTool(runId, name, exception);
            return Map.of("ok", false, "tool", name, "error", safeError(exception), "nextGoal", state.nextGoal());
        }
    }

    private String executionSystemPrompt(ExecutionState state) {
        return "你是获得一次性授权的自主电网故障仿真调控Agent。所有操作仅作用于系统仿真。"
                + "你必须根据每次工具返回的新状态自主选择下一项工具，一次只调用一个工具。"
                + "目标依次是创建可追踪处置任务、建立全部正式候选、运行全部逐时仿真、选择最低风险安全方案、"
                + "创建自主调控会话，并根据nextAction亲自完成CONFIRM、OPEN_SOURCE、CLOSE_TIE、TRANSFER、VERIFY。"
                + "不能跳过安全顺序，不能声称控制真实设备。只有调控状态COMPLETED且verified=true时才能输出最终Markdown总结。"
                + "当前系统状态：" + writeJson(state.summary());
    }

    private List<Map<String, Object>> executionTools() {
        return List.of(
                functionTool("create_response_case", "创建并持久化正式异常处置任务。尚无planId时调用。", Map.of(), List.of()),
                functionTool("build_formal_scenarios", "为正式任务建立全部候选转供方案并保存操作步骤。", Map.of(), List.of()),
                functionTool("run_formal_simulations", "启动并等待全部候选路径的正式逐时仿真。", Map.of(), List.of()),
                functionTool("select_lowest_risk_scenario", "从正式仿真结果中选择最高负载率最低的安全方案。", Map.of(), List.of()),
                functionTool("create_autonomous_control", "为选中的安全方案创建由Agent操作的仿真调控会话。", Map.of(), List.of()),
                functionTool(
                        "execute_simulated_control_action",
                        "执行一项仿真调控动作。必须严格使用上一次结果中的nextAction.actionType。",
                        Map.of("actionType", stringProperty("CONFIRM、OPEN_SOURCE、CLOSE_TIE、TRANSFER或VERIFY")),
                        List.of("actionType")),
                functionTool("inspect_control_state", "读取并核验当前仿真调控状态、动作记录和下一项动作。", Map.of(), List.of()));
    }

    private Map<String, Object> controlStateSummary(Map<String, Object> control) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "id",
                "status",
                "currentStepNo",
                "sourceSwitchState",
                "tieSwitchState",
                "supplyState",
                "transferred",
                "verified")) {
            result.put(key, control.get(key));
        }
        return result;
    }

    private Map<String, Object> compactControl(Map<String, Object> control) {
        Map<String, Object> result = new LinkedHashMap<>(controlStateSummary(control));
        result.put("nextAction", control.get("nextAction"));
        result.put("effect", control.get("effect"));
        Object actions = control.get("actions");
        result.put("actionCount", actions instanceof List<?> ? ((List<?>) actions).size() : 0);
        result.put("simulationOnly", true);
        return result;
    }

    private List<Map<String, Object>> compactScenarios(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        return items.stream().map(this::compactScenario).toList();
    }

    private Map<String, Object> compactScenario(Object value) {
        Map<String, Object> source = castMap(value);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of(
                "id",
                "status",
                "scenarioName",
                "sourceFeederCode",
                "targetFeederCode",
                "targetTransformerCode",
                "tieSwitchCode",
                "feasible",
                "maximumLoadRate",
                "maximumLoadPercent",
                "violations",
                "steps")) {
            if (source.containsKey(key)) {
                result.put(key, source.get(key));
            }
        }
        Map<String, Object> summary = castMap(source.get("summary"));
        if (!summary.isEmpty()) {
            Map<String, Object> compactSummary = new LinkedHashMap<>();
            for (String key :
                    List.of("feasible", "maximumLoadRate", "maximumLoadPercent", "violations", "dataSource")) {
                if (summary.containsKey(key)) {
                    compactSummary.put(key, summary.get(key));
                }
            }
            result.put("summary", compactSummary);
        }
        return result;
    }

    private void saveExecutionState(long sessionId, ExecutionState state) {
        Map<String, Object> draft = jdbcTemplate.queryForObject(
                "SELECT draft_json FROM planning_agent_session WHERE id=?",
                (rs, rowNum) -> parseMap(rs.getString(1)),
                sessionId);
        draft.put("execution", state.summary());
        draft.put("agentMode", "AUTONOMOUS_SIMULATION_CONTROL");
        jdbcTemplate.update(
                "UPDATE planning_agent_session SET draft_json=?, linked_plan_id=?, updated_at=NOW(3) WHERE id=?",
                writeJson(draft),
                state.planId,
                sessionId);
    }

    private void accumulateRunTokens(long runId, int inputTokens, int outputTokens) {
        jdbcTemplate.update(
                "UPDATE planning_agent_run SET input_tokens=input_tokens+?, output_tokens=output_tokens+? WHERE id=?",
                inputTokens,
                outputTokens,
                runId);
    }

    private void executeResponseWorkflow(Map<String, Object> approval, Map<String, Object> payload) {
        long runId = ((Number) approval.get("runId")).longValue();
        long sessionId = ((Number) approval.get("sessionId")).longValue();
        startTool(
                runId,
                "create_response_case",
                "正在创建可追踪的异常处置任务",
                Map.of("transformerCode", payload.get("transformerCode"), "planName", payload.get("planName")));
        Map<String, Object> plan = planningService.createPlan(
                String.valueOf(payload.get("planName")),
                String.valueOf(payload.get("reason")),
                "TRANSFORMER",
                String.valueOf(payload.get("transformerCode")),
                LocalDateTime.parse(String.valueOf(payload.get("startTime"))),
                LocalDateTime.parse(String.valueOf(payload.get("endTime"))),
                new BigDecimal(String.valueOf(payload.get("safetyLimit"))),
                String.valueOf(payload.get("profileType")),
                null,
                UserContext.get().getUsername());
        long planId = ((Number) plan.get("id")).longValue();
        completeRunningTool(
                runId,
                "create_response_case",
                "处置任务 #" + planId + " 已创建并持久化",
                Map.of(
                        "planId", planId,
                        "status", plan.get("status"),
                        "verification", Map.of("databaseIdCreated", planId > 0)));
        startTool(
                runId,
                "build_formal_scenarios",
                "正在把候选路径转换为正式仿真方案",
                Map.of(
                        "planId",
                        planId,
                        "recommendedTieSwitch",
                        castMap(payload.get("recommended")).get("tieSwitchCode")));
        List<Map<String, Object>> candidates = planningService.generateCandidates(planId);
        Map<String, Object> recommended = castMap(payload.get("recommended"));
        for (Map<String, Object> candidate : candidates) {
            if (Objects.equals(candidate.get("tieSwitchCode"), recommended.get("tieSwitchCode"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> steps = (List<Map<String, String>>) (List<?>) recommended.get("steps");
                planningService.saveSteps(((Number) candidate.get("id")).longValue(), steps);
            }
        }
        completeRunningTool(
                runId,
                "build_formal_scenarios",
                "已生成" + candidates.size() + "个正式方案",
                Map.of(
                        "scenarioCount", candidates.size(),
                        "scenarioIds",
                                candidates.stream().map(item -> item.get("id")).toList(),
                        "verification", Map.of("persistedScenarioCount", candidates.size())));
        startTool(
                runId,
                "run_formal_simulations",
                "正在并行执行全部路径的正式逐时仿真",
                Map.of("planId", planId, "scenarioCount", candidates.size()));
        List<Long> taskIds = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            Map<String, Object> task = planningService.startSimulation(((Number) candidate.get("id")).longValue());
            taskIds.add(((Number) task.get("id")).longValue());
        }
        waitForSimulations(runId, taskIds);
        Map<String, Object> completedPlan = planningService.planDetail(planId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> completedScenarios = (List<Map<String, Object>>) completedPlan.get("scenarios");
        Map<String, Object> selected = completedScenarios.stream()
                .filter(item -> "SUCCEEDED".equals(item.get("status")))
                .filter(item -> Boolean.TRUE.equals(castMap(item.get("summary")).get("feasible")))
                .min(java.util.Comparator.comparingDouble(
                        item -> ((Number) castMap(item.get("summary")).get("maximumLoadRate")).doubleValue()))
                .orElseThrow(() -> new IllegalArgumentException("正式仿真没有找到通过安全校核的路径，Agent已停止执行"));
        completeRunningTool(
                runId,
                "run_formal_simulations",
                "全部正式仿真完成，已选择风险最低的安全路径",
                Map.of(
                        "taskIds",
                        taskIds,
                        "selectedScenarioId",
                        selected.get("id"),
                        "summary",
                        selected.get("summary"),
                        "verification",
                        Map.of(
                                "finishedTaskCount",
                                taskIds.size(),
                                "selectedScenarioPersisted",
                                selected.get("id") != null)));
        startTool(
                runId,
                "prepare_manual_control",
                "正在准备可由用户逐步操作的仿真调控台",
                Map.of("planId", planId, "scenarioId", selected.get("id")));
        Map<String, Object> control = controlService.create(
                ((Number) selected.get("id")).longValue(), payload.get("transformerCode") + " · Agent准备的异常处置演练");
        long controlSessionId = ((Number) control.get("id")).longValue();
        completeRunningTool(
                runId,
                "prepare_manual_control",
                "手动仿真调控台已就绪，Agent任务产物可以直接使用",
                Map.of(
                        "controlSessionId", controlSessionId,
                        "operationChecklist", control.get("operationChecklist"),
                        "verification", Map.of("controlSessionPersisted", controlSessionId > 0)));
        jdbcTemplate.update(
                "UPDATE planning_agent_session SET linked_plan_id=?, updated_at=NOW(3) WHERE id=?", planId, sessionId);
        Map<String, Object> draft = jdbcTemplate.queryForObject(
                "SELECT draft_json FROM planning_agent_session WHERE id=?",
                (rs, rowNum) -> parseMap(rs.getString(1)),
                sessionId);
        draft.put(
                "execution",
                Map.of(
                        "planId",
                        planId,
                        "scenarioId",
                        selected.get("id"),
                        "controlSessionId",
                        controlSessionId,
                        "taskIds",
                        taskIds,
                        "status",
                        "READY_FOR_MANUAL_CONTROL"));
        jdbcTemplate.update("UPDATE planning_agent_session SET draft_json=? WHERE id=?", writeJson(draft), sessionId);
        appendAssistant(
                sessionId,
                "任务已经真正执行完成：我创建了处置任务 #" + planId + "，并行完成了" + taskIds.size()
                        + "条路径的正式仿真，自动选出风险最低的安全路径，还准备好了手动仿真调控台 #"
                        + controlSessionId + "。现在可以直接打开调控台，按顺序查看并执行每一步仿真操作。",
                Map.of(
                        "planId", planId,
                        "scenarioId", selected.get("id"),
                        "controlSessionId", controlSessionId,
                        "simulationTaskIds", taskIds));
    }

    private void waitForSimulations(long runId, List<Long> taskIds) {
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled(runId)) {
                for (long taskId : taskIds) {
                    planningService.cancelSimulation(taskId);
                }
                throw new IllegalStateException("用户已取消Agent任务");
            }
            int finished = 0;
            int failed = 0;
            double progress = 0;
            for (long taskId : taskIds) {
                Map<String, Object> task = planningService.simulationTask(taskId);
                String status = String.valueOf(task.get("status"));
                progress += ((Number) task.get("progress")).doubleValue();
                if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
                    finished++;
                }
                if (Set.of("FAILED", "CANCELLED").contains(status)) {
                    failed++;
                }
            }
            jdbcTemplate.update(
                    "UPDATE planning_agent_run SET current_stage=? WHERE id=?",
                    "正式仿真 " + finished + "/" + taskIds.size() + "，平均进度"
                            + Math.round(progress / Math.max(1, taskIds.size())) + "%",
                    runId);
            if (finished == taskIds.size()) {
                if (failed == taskIds.size()) {
                    throw new IllegalStateException("所有正式仿真均失败，Agent已停止执行");
                }
                return;
            }
            try {
                Thread.sleep(350);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Agent任务已中断", exception);
            }
        }
        throw new IllegalStateException("正式仿真等待超时，可稍后从会话恢复查看");
    }

    private void completeRunningTool(long runId, String toolName, String summary, Object detail) {
        List<Map<String, Object>> running = jdbcTemplate.query(
                "SELECT id, detail_json, created_at FROM planning_agent_step "
                        + "WHERE run_id=? AND tool_name=? AND status='RUNNING' ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("detailJson", rs.getString("detail_json"));
                    row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return row;
                },
                runId,
                toolName);
        if (running.isEmpty()) {
            return;
        }
        Map<String, Object> step = running.get(0);
        Map<String, Object> trace = parseMap((String) step.get("detailJson"));
        if (!trace.containsKey("action")) {
            trace.put("action", "执行领域工具 " + toolName);
        }
        Object output = detail;
        if (detail instanceof Map<?, ?>) {
            Map<String, Object> outputMap = new LinkedHashMap<>(castMap(detail));
            Object verification = outputMap.remove("verification");
            if (verification != null) {
                trace.put("verification", verification);
            }
            output = outputMap;
        }
        trace.put("output", output == null ? Map.of("completed", true) : output);
        LocalDateTime finishedAt = LocalDateTime.now(ZONE);
        trace.put("finishedAt", finishedAt);
        trace.put(
                "durationMs",
                Math.max(
                        0,
                        Duration.between((LocalDateTime) step.get("createdAt"), finishedAt)
                                .toMillis()));
        jdbcTemplate.update(
                "UPDATE planning_agent_step SET status='SUCCEEDED', summary_text=?, detail_json=? WHERE id=?",
                summary,
                writeJson(trace),
                step.get("id"));
    }

    private void startTool(long runId, String toolName, String action, Object input) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("action", action);
        trace.put("input", input == null ? Map.of() : input);
        trace.put("startedAt", LocalDateTime.now(ZONE));
        addStep(runId, "TOOL", toolName, "RUNNING", action, trace);
    }

    private void failRunningTools(long runId, Exception exception) {
        List<Long> ids = jdbcTemplate.query(
                "SELECT id FROM planning_agent_step WHERE run_id=? AND status='RUNNING'",
                (rs, rowNum) -> rs.getLong(1),
                runId);
        for (Long id : ids) {
            jdbcTemplate.update(
                    "UPDATE planning_agent_step SET status='FAILED', summary_text=?, detail_json=? WHERE id=?",
                    "工具执行失败：" + safeError(exception),
                    writeJson(Map.of(
                            "action", "工具执行未完成",
                            "output", Map.of("error", safeError(exception)),
                            "finishedAt", LocalDateTime.now(ZONE))),
                    id);
        }
    }

    private void failRunningTool(long runId, String toolName, Exception exception) {
        List<Map<String, Object>> running = jdbcTemplate.query(
                "SELECT id, detail_json, created_at FROM planning_agent_step "
                        + "WHERE run_id=? AND tool_name=? AND status='RUNNING' ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("detailJson", rs.getString("detail_json"));
                    row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
                    return row;
                },
                runId,
                toolName);
        if (running.isEmpty()) {
            return;
        }
        Map<String, Object> step = running.get(0);
        Map<String, Object> trace = parseMap((String) step.get("detailJson"));
        LocalDateTime finishedAt = LocalDateTime.now(ZONE);
        trace.put("output", Map.of("error", safeError(exception)));
        trace.put("finishedAt", finishedAt);
        trace.put(
                "durationMs",
                Math.max(
                        0,
                        Duration.between((LocalDateTime) step.get("createdAt"), finishedAt)
                                .toMillis()));
        jdbcTemplate.update(
                "UPDATE planning_agent_step SET status='FAILED', summary_text=?, detail_json=? WHERE id=?",
                toolPlainName(toolName) + "未完成：" + safeError(exception),
                writeJson(trace),
                step.get("id"));
    }

    private void prepareSubmitApproval(long runId, Map<String, Object> session) {
        long planId = ((Number) session.get("linkedPlanId")).longValue();
        Map<String, Object> plan = planningService.planDetail(planId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenarios = (List<Map<String, Object>>) plan.get("scenarios");
        Map<String, Object> selected = scenarios.stream()
                .filter(item -> "SUCCEEDED".equals(item.get("status")))
                .filter(item -> Boolean.TRUE.equals(castMap(item.get("summary")).get("feasible")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("关联计划还没有通过校核的方案，暂时不能提交"));
        long sessionId = ((Number) session.get("id")).longValue();
        long userId = UserContext.get().getUserId();
        Map<String, Object> payload = Map.of("planId", planId, "scenarioId", selected.get("id"));
        jdbcTemplate.update(
                "INSERT INTO planning_agent_approval(run_id, session_id, user_id, action_type, idempotency_key, "
                        + "preview_json, payload_json) VALUES (?, ?, ?, 'SUBMIT_PLAN', ?, ?, ?)",
                runId,
                sessionId,
                userId,
                UUID.randomUUID().toString(),
                writeJson(Map.of(
                        "title",
                        "准备提交检修计划审核",
                        "planId",
                        planId,
                        "scenarioId",
                        selected.get("id"),
                        "summary",
                        selected.get("summary"))),
                writeJson(payload));
        appendAssistant(sessionId, "已找到通过校核的方案。请确认是否将检修计划提交管理员审核。", payload);
        finish(runId, "WAITING_APPROVAL", "等待确认提交审核", 0, 0, null);
    }

    private String deterministicSummary(
            Extraction extraction, Map<String, Object> preview, Map<String, Object> recommended) {
        if (recommended.isEmpty()) {
            return "没有找到可用的联络路径，当前不能形成转供方案。请调整检修范围或补充拓扑。";
        }
        String conclusion = Boolean.TRUE.equals(recommended.get("feasible")) ? "临时容量校核通过" : "临时容量校核未通过，需要调整检修时间或转供路径";
        return "已完成" + extraction.transformerCode() + "检修转供分析。推荐通过"
                + recommended.get("tieSwitchCode") + "转入" + recommended.get("targetFeederCode")
                + "，由" + recommended.get("targetTransformerCode") + "承接负荷；最大预计负载率为"
                + recommended.get("maximumLoadPercent") + "%。“" + conclusion + "”。数据来源："
                + preview.get("dataSource") + "。所有开关步骤仅用于规划，执行前仍需现场人员复核。";
    }

    private Map<String, Object> runSnapshot(long runId, long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM planning_agent_run WHERE id=? AND user_id=?", this::runRow, runId, userId);
        if (rows.isEmpty()) {
            throw new AccessDeniedException("Agent运行不存在或不属于当前用户");
        }
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        result.put(
                "steps",
                jdbcTemplate.query(
                        "SELECT * FROM planning_agent_step WHERE run_id=? ORDER BY step_no", this::stepRow, runId));
        result.put(
                "approvals",
                jdbcTemplate.query(
                        "SELECT * FROM planning_agent_approval WHERE run_id=? ORDER BY id", this::approvalRow, runId));
        return result;
    }

    private Map<String, Object> requireSession(long sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM planning_agent_session WHERE id=? AND user_id=?",
                this::sessionRow,
                sessionId,
                UserContext.get().getUserId());
        if (rows.isEmpty()) {
            throw new AccessDeniedException("Agent会话不存在或不属于当前用户");
        }
        return rows.get(0);
    }

    private Map<String, Object> requireApproval(long approvalId, long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT * FROM planning_agent_approval WHERE id=? AND user_id=?",
                this::approvalRow,
                approvalId,
                userId);
        if (rows.isEmpty()) {
            throw new AccessDeniedException("审批不存在或不属于当前用户");
        }
        return rows.get(0);
    }

    private void addStep(long runId, String stepType, String toolName, String status, String summary, Object detail) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(step_no), 0) + 1 FROM planning_agent_step WHERE run_id=?", Integer.class, runId);
        jdbcTemplate.update(
                "INSERT INTO planning_agent_step(run_id, step_no, step_type, tool_name, status, summary_text, "
                        + "detail_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                runId,
                next,
                stepType,
                toolName,
                status,
                summary,
                detail == null ? null : writeJson(detail));
    }

    private void appendAssistant(long sessionId, String content, Object metadata) {
        jdbcTemplate.update(
                "INSERT INTO planning_agent_message(session_id, role_code, content_text, metadata_json) "
                        + "VALUES (?, 'ASSISTANT', ?, ?)",
                sessionId,
                content,
                metadata == null ? null : writeJson(metadata));
        jdbcTemplate.update("UPDATE planning_agent_session SET updated_at=NOW(3) WHERE id=?", sessionId);
    }

    private void updateStage(long runId, String stage) {
        jdbcTemplate.update("UPDATE planning_agent_run SET current_stage=? WHERE id=?", stage, runId);
    }

    private void finish(long runId, String status, String stage, int inputTokens, int outputTokens, String error) {
        jdbcTemplate.update(
                "UPDATE planning_agent_run SET status=?, current_stage=?, input_tokens=?, output_tokens=?, "
                        + "error_message=?, finished_at=CASE WHEN ? IN ('RUNNING','WAITING_APPROVAL') THEN NULL "
                        + "ELSE NOW(3) END WHERE id=?",
                status,
                stage,
                inputTokens,
                outputTokens,
                error,
                status,
                runId);
    }

    private boolean isCancelled(long runId) {
        return "CANCEL_REQUESTED"
                .equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM planning_agent_run WHERE id=?", String.class, runId));
    }

    private void finishCancelled(long runId) {
        finish(runId, "CANCELLED", "已取消", 0, 0, null);
    }

    private Map<String, Object> sessionRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("title", rs.getString("title"));
        row.put("linkedPlanId", nullableLong(rs, "linked_plan_id"));
        row.put("draftJson", rs.getString("draft_json"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> messageRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("sessionId", rs.getLong("session_id"));
        row.put("role", rs.getString("role_code"));
        row.put("content", rs.getString("content_text"));
        row.put("metadataJson", rs.getString("metadata_json"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> runRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("sessionId", rs.getLong("session_id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("status", rs.getString("status"));
        row.put("currentStage", rs.getString("current_stage"));
        row.put("errorMessage", rs.getString("error_message"));
        row.put("inputTokens", rs.getInt("input_tokens"));
        row.put("outputTokens", rs.getInt("output_tokens"));
        row.put("startedAt", rs.getTimestamp("started_at").toLocalDateTime());
        Timestamp finished = rs.getTimestamp("finished_at");
        row.put("finishedAt", finished == null ? null : finished.toLocalDateTime());
        return row;
    }

    private Map<String, Object> stepRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("runId", rs.getLong("run_id"));
        row.put("stepNo", rs.getInt("step_no"));
        row.put("stepType", rs.getString("step_type"));
        row.put("toolName", rs.getString("tool_name"));
        row.put("status", rs.getString("status"));
        row.put("summary", rs.getString("summary_text"));
        row.put("detailJson", rs.getString("detail_json"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> approvalRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("runId", rs.getLong("run_id"));
        row.put("sessionId", rs.getLong("session_id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("actionType", rs.getString("action_type"));
        row.put("status", rs.getString("status"));
        row.put("previewJson", rs.getString("preview_json"));
        row.put("payloadJson", rs.getString("payload_json"));
        Timestamp decided = rs.getTimestamp("decided_at");
        row.put("decidedAt", decided == null ? null : decided.toLocalDateTime());
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String upperOrNull(Object value) {
        String result = value == null ? null : String.valueOf(value).trim();
        return result == null || result.isBlank() || "null".equalsIgnoreCase(result) ? null : result.toUpperCase();
    }

    private String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank() || "null".equalsIgnoreCase(String.valueOf(value))) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null || "null".equalsIgnoreCase(String.valueOf(value))) {
            return null;
        }
        return LocalDateTime.parse(String.valueOf(value));
    }

    private BigDecimal decimalOrNull(Object value) {
        if (value == null || "null".equalsIgnoreCase(String.valueOf(value))) {
            return null;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String stripCodeFence(String content) {
        String value = content.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return value;
    }

    private Map<String, Object> parseMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent结构化数据格式错误", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Agent数据序列化失败", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> castList(Object value) {
        return value instanceof List ? (List<Object>) value : List.of();
    }

    private String safeError(Exception exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) {
            return "Agent运行失败";
        }
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class InvestigationState {
        private final Extraction extraction;
        private Map<String, Object> liveEvidence = Map.of();
        private Map<String, Object> topologyEvidence = Map.of();
        private Map<String, Object> preview = Map.of();
        private Map<String, Object> recommended = Map.of();
        private boolean approvalCreated;
        private final Set<String> completedTools = new java.util.LinkedHashSet<>();

        private InvestigationState(Extraction extraction) {
            this.extraction = extraction;
        }

        private List<String> missingEvidenceTools() {
            return List.of("inspect_live_anomaly", "inspect_planning_topology", "evaluate_transfer_options").stream()
                    .filter(tool -> !completedTools.contains(tool))
                    .toList();
        }

        private boolean hasFeasibleRecommendation() {
            return !recommended.isEmpty() && Boolean.TRUE.equals(recommended.get("feasible"));
        }
    }

    private static final class ExecutionState {
        private final Map<String, Object> payload;
        private Long planId;
        private List<Map<String, Object>> candidates = List.of();
        private List<Long> taskIds = List.of();
        private Long selectedScenarioId;
        private Long controlSessionId;
        private String controlStatus;
        private boolean controlVerified;
        private int controlStepNo;
        private String nextControlAction;

        private ExecutionState(Map<String, Object> payload) {
            this.payload = payload;
        }

        private void requirePlan() {
            if (planId == null) {
                throw new IllegalStateException("需要先创建正式异常处置任务");
            }
        }

        private void requireCandidates() {
            requirePlan();
            if (candidates.isEmpty()) {
                throw new IllegalStateException("需要先建立正式候选方案");
            }
        }

        private void requireSimulations() {
            requireCandidates();
            if (taskIds.isEmpty()) {
                throw new IllegalStateException("需要先运行正式逐时仿真");
            }
        }

        private void requireSelectedScenario() {
            requireSimulations();
            if (selectedScenarioId == null) {
                throw new IllegalStateException("需要先选择最低风险的安全方案");
            }
        }

        private void requireControl() {
            requireSelectedScenario();
            if (controlSessionId == null) {
                throw new IllegalStateException("需要先创建自主仿真调控会话");
            }
        }

        private void updateControl(Map<String, Object> control) {
            controlSessionId = ((Number) control.get("id")).longValue();
            controlStatus = String.valueOf(control.get("status"));
            controlVerified = Boolean.TRUE.equals(control.get("verified"));
            controlStepNo = ((Number) control.getOrDefault("currentStepNo", 0)).intValue();
            Map<String, Object> nextAction = control.get("nextAction") instanceof Map<?, ?>
                    ? castStaticMap(control.get("nextAction"))
                    : Map.of();
            nextControlAction = nextAction.isEmpty() ? null : String.valueOf(nextAction.get("actionType"));
        }

        private boolean controlCompleted() {
            return "COMPLETED".equals(controlStatus) && controlVerified;
        }

        private String nextGoal() {
            if (planId == null) {
                return "创建正式异常处置任务";
            }
            if (candidates.isEmpty()) {
                return "建立全部正式候选方案";
            }
            if (taskIds.isEmpty()) {
                return "运行并等待全部候选的正式逐时仿真";
            }
            if (selectedScenarioId == null) {
                return "选择风险最低且通过安全线的方案";
            }
            if (controlSessionId == null) {
                return "创建自主仿真调控会话";
            }
            if (!controlCompleted()) {
                return nextControlAction == null ? "读取调控状态并确定下一项安全动作" : "执行下一项仿真调控动作 " + nextControlAction;
            }
            return "汇总已经完成并核验通过的自主仿真调控结果";
        }

        private Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", "AUTONOMOUS_SIMULATION_CONTROL");
            result.put("planId", planId);
            result.put("candidateCount", candidates.size());
            result.put("simulationTaskIds", taskIds);
            result.put("selectedScenarioId", selectedScenarioId);
            result.put("controlSessionId", controlSessionId);
            result.put("controlStatus", controlStatus);
            result.put("controlStepNo", controlStepNo);
            result.put("nextControlAction", nextControlAction);
            result.put("verified", controlVerified);
            result.put("completed", controlCompleted());
            result.put("nextGoal", nextGoal());
            return result;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castStaticMap(Object value) {
            return (Map<String, Object>) value;
        }
    }

    private record Extraction(
            String intent,
            String transformerCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal safetyLimit,
            String profileType,
            int inputTokens,
            int outputTokens) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("intent", intent);
            result.put("transformerCode", transformerCode);
            result.put("startTime", startTime);
            result.put("endTime", endTime);
            result.put("safetyLimit", safetyLimit);
            result.put("profileType", profileType);
            return result;
        }
    }
}
