package com.loadflex.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.server.security.AccessControlService;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A persisted, simulation-only switching console. It never talks to physical grid equipment. */
@Service
public class GridControlService {
    private static final List<String> ACTIONS = List.of("CONFIRM", "OPEN_SOURCE", "CLOSE_TIE", "TRANSFER", "VERIFY");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final MaintenancePlanningService planningService;

    public GridControlService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            MaintenancePlanningService planningService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.planningService = planningService;
    }

    @Transactional
    public Map<String, Object> create(long scenarioId, String name) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> scenario = planningService.scenarioDetail(scenarioId);
        Map<String, Object> summary = map(scenario.get("summary"));
        if (!"SUCCEEDED".equals(scenario.get("status")) || !Boolean.TRUE.equals(summary.get("feasible"))) {
            throw new IllegalArgumentException("只有已经完成且通过安全校核的方案才能进入仿真调控台");
        }
        long userId = UserContext.get().getUserId();
        long planId = ((Number) scenario.get("planId")).longValue();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO grid_control_session(plan_id, scenario_id, created_by, session_name) "
                                    + "VALUES (?, ?, ?, ?)",
                            new String[] {"id"});
                    statement.setLong(1, planId);
                    statement.setLong(2, scenarioId);
                    statement.setLong(3, userId);
                    statement.setString(
                            4,
                            name == null || name.isBlank() ? scenario.get("scenarioName") + " · 手动仿真调控" : name.trim());
                    return statement;
                },
                holder);
        long id = Objects.requireNonNull(holder.getKey()).longValue();
        audit(userId, "CREATE_CONTROL_SESSION", id, Map.of("scenarioId", scenarioId));
        return detail(id);
    }

    public List<Map<String, Object>> listForPlan(long planId) {
        planningService.planDetail(planId);
        return jdbcTemplate.query(
                "SELECT * FROM grid_control_session WHERE plan_id=? ORDER BY id DESC", this::sessionRow, planId);
    }

    public Map<String, Object> detail(long sessionId) {
        Map<String, Object> session = requireReadable(sessionId);
        Map<String, Object> scenario = planningService.scenarioDetail(((Number) session.get("scenarioId")).longValue());
        Map<String, Object> result = new LinkedHashMap<>(session);
        result.put("scenario", scenario);
        result.put(
                "actions",
                jdbcTemplate.query(
                        "SELECT a.*, u.display_name operator_name FROM grid_control_action a "
                                + "JOIN app_user u ON u.id=a.operator_id WHERE a.control_session_id=? "
                                + "ORDER BY sequence_no",
                        this::actionRow,
                        sessionId));
        int currentStep = ((Number) session.get("currentStepNo")).intValue();
        result.put(
                "nextAction",
                currentStep >= ACTIONS.size() ? null : actionDefinition(ACTIONS.get(currentStep), scenario));
        result.put("operationChecklist", checklist(currentStep, scenario));
        result.put("effect", effect(session, scenario));
        result.put("frame", currentStep >= 4 ? latestFrame(scenario) : List.of());
        result.put("simulationOnly", true);
        return result;
    }

    @Transactional
    public Map<String, Object> execute(long sessionId, String actionType, String idempotencyKey) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> session = requireOwned(sessionId);
        if ("COMPLETED".equals(session.get("status")) || "ROLLED_BACK".equals(session.get("status"))) {
            throw new IllegalArgumentException("本次调控已经结束，如需再次演练请新建调控会话");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey;
        List<Map<String, Object>> existing = jdbcTemplate.query(
                "SELECT a.*, u.display_name operator_name FROM grid_control_action a "
                        + "JOIN app_user u ON u.id=a.operator_id WHERE a.idempotency_key=?",
                this::actionRow,
                key);
        if (!existing.isEmpty()) {
            return detail(sessionId);
        }
        int current = ((Number) session.get("currentStepNo")).intValue();
        if (current >= ACTIONS.size()) {
            throw new IllegalArgumentException("所有调控步骤已经完成");
        }
        String expected = ACTIONS.get(current);
        if (!expected.equals(actionType)) {
            throw new IllegalArgumentException("为防止误操作，当前只能执行：“" + label(expected) + "”");
        }
        Map<String, Object> scenario = planningService.scenarioDetail(((Number) session.get("scenarioId")).longValue());
        Map<String, Object> before = stateSnapshot(session);
        State next = transition(expected, session);
        int updated = jdbcTemplate.update(
                "UPDATE grid_control_session SET status=?, current_step_no=?, source_switch_state=?, "
                        + "tie_switch_state=?, supply_state=?, transferred=?, verified=?, version=version+1, "
                        + "completed_at=CASE WHEN ?='COMPLETED' THEN NOW(3) ELSE NULL END "
                        + "WHERE id=? AND version=?",
                next.status(),
                current + 1,
                next.sourceSwitch(),
                next.tieSwitch(),
                next.supply(),
                next.transferred(),
                next.verified(),
                next.status(),
                sessionId,
                session.get("version"));
        if (updated != 1) {
            throw new IllegalStateException("调控状态已被其他操作更新，请刷新后重试");
        }
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("status", next.status());
        after.put("sourceSwitchState", next.sourceSwitch());
        after.put("tieSwitchState", next.tieSwitch());
        after.put("supplyState", next.supply());
        after.put("transferred", next.transferred());
        after.put("verified", next.verified());
        Map<String, Object> definition = actionDefinition(expected, scenario);
        LoginUser user = UserContext.get();
        jdbcTemplate.update(
                "INSERT INTO grid_control_action(control_session_id, sequence_no, action_type, device_code, "
                        + "instruction, before_state_json, after_state_json, result_json, operator_id, "
                        + "idempotency_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                sessionId,
                current + 1,
                expected,
                definition.get("deviceCode"),
                definition.get("instruction"),
                json(before),
                json(after),
                json(Map.of("message", resultMessage(expected), "simulationOnly", true)),
                user.getUserId(),
                key);
        audit(user.getUserId(), "EXECUTE_SIMULATED_CONTROL", sessionId, Map.of("action", expected));
        return detail(sessionId);
    }

    @Transactional
    public Map<String, Object> rollback(long sessionId, String idempotencyKey) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> session = requireOwned(sessionId);
        if (((Number) session.get("currentStepNo")).intValue() == 0) {
            return detail(sessionId);
        }
        if ("ROLLED_BACK".equals(session.get("status"))) {
            return detail(sessionId);
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey;
        Map<String, Object> before = stateSnapshot(session);
        jdbcTemplate.update(
                "UPDATE grid_control_session SET status='ROLLED_BACK', source_switch_state='CLOSED', "
                        + "tie_switch_state='OPEN', supply_state='NORMAL', transferred=0, verified=0, "
                        + "version=version+1, completed_at=NOW(3) WHERE id=?",
                sessionId);
        Map<String, Object> after = Map.of(
                "status", "ROLLED_BACK",
                "sourceSwitchState", "CLOSED",
                "tieSwitchState", "OPEN",
                "supplyState", "NORMAL",
                "transferred", false,
                "verified", false);
        LoginUser user = UserContext.get();
        int sequence = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM grid_control_action WHERE control_session_id=?",
                Integer.class,
                sessionId);
        jdbcTemplate.update(
                "INSERT INTO grid_control_action(control_session_id, sequence_no, action_type, instruction, "
                        + "before_state_json, after_state_json, result_json, operator_id, idempotency_key) "
                        + "VALUES (?, ?, 'ROLLBACK', '恢复原供电状态并结束本次演练', ?, ?, ?, ?, ?)",
                sessionId,
                sequence,
                json(before),
                json(after),
                json(Map.of("message", "已恢复原供电拓扑", "simulationOnly", true)),
                user.getUserId(),
                key);
        audit(user.getUserId(), "ROLLBACK_SIMULATED_CONTROL", sessionId, Map.of());
        return detail(sessionId);
    }

    private State transition(String action, Map<String, Object> session) {
        String source = String.valueOf(session.get("sourceSwitchState"));
        String tie = String.valueOf(session.get("tieSwitchState"));
        String supply = String.valueOf(session.get("supplyState"));
        boolean transferred = Boolean.TRUE.equals(session.get("transferred"));
        boolean verified = Boolean.TRUE.equals(session.get("verified"));
        return switch (action) {
            case "CONFIRM" -> new State("RUNNING", source, tie, supply, transferred, verified);
            case "OPEN_SOURCE" -> new State("RUNNING", "OPEN", tie, "INTERRUPTED", false, false);
            case "CLOSE_TIE" -> new State("RUNNING", source, "CLOSED", "INTERRUPTED", false, false);
            case "TRANSFER" -> new State("RUNNING", source, tie, "BACKUP", true, false);
            case "VERIFY" -> new State("COMPLETED", source, tie, "BACKUP", true, true);
            default -> throw new IllegalArgumentException("不支持的调控动作：" + action);
        };
    }

    private List<Map<String, Object>> checklist(int current, Map<String, Object> scenario) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < ACTIONS.size(); index++) {
            Map<String, Object> item = new LinkedHashMap<>(actionDefinition(ACTIONS.get(index), scenario));
            item.put("stepNo", index + 1);
            item.put("status", index < current ? "COMPLETED" : index == current ? "CURRENT" : "PENDING");
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> actionDefinition(String action, Map<String, Object> scenario) {
        String sourceFeeder = String.valueOf(scenario.get("sourceFeederCode"));
        String tieSwitch = String.valueOf(scenario.get("tieSwitchCode"));
        String transformer = String.valueOf(scenario.get("targetTransformerCode"));
        return switch (action) {
            case "CONFIRM" -> action(action, null, "确认故障设备和推荐路径，开始仿真演练", "确认并开始");
            case "OPEN_SOURCE" -> action(action, "S-" + sourceFeeder, "断开原供电线路，隔离异常设备", "断开原线路");
            case "CLOSE_TIE" -> action(action, tieSwitch, "接通备用线路，为负荷转移建立通路", "接通备用线路");
            case "TRANSFER" -> action(action, transformer, "把受影响负荷转移到备用设备", "转移负荷");
            case "VERIFY" -> action(action, transformer, "核验转移后的供电状态和最高负载率", "核验结果");
            default -> Map.of();
        };
    }

    private Map<String, Object> action(String type, String device, String instruction, String buttonText) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionType", type);
        result.put("deviceCode", device);
        result.put("instruction", instruction);
        result.put("buttonText", buttonText);
        return result;
    }

    private Map<String, Object> effect(Map<String, Object> session, Map<String, Object> scenario) {
        int step = ((Number) session.get("currentStepNo")).intValue();
        Map<String, Object> summary = map(scenario.get("summary"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("phase", step == 0 ? "BEFORE" : step < 4 ? "SWITCHING" : "AFTER");
        result.put("supplyState", session.get("supplyState"));
        result.put("affectedTransformer", firstLoadBlock(scenario));
        result.put("targetTransformer", scenario.get("targetTransformerCode"));
        result.put("maximumLoadPercent", step >= 4 ? summary.get("maximumLoadPercent") : null);
        result.put(
                "message",
                switch (String.valueOf(session.get("supplyState"))) {
                    case "INTERRUPTED" -> "原线路已隔离，当前处于短暂断电阶段；继续接通备用线路并转移负荷。";
                    case "BACKUP" -> "受影响区域已由备用路径供电，图中负载已切换为仿真后的数值。";
                    default -> "当前仍由原线路正常供电，尚未改变拓扑。";
                });
        return result;
    }

    private List<Map<String, Object>> latestFrame(Map<String, Object> scenario) {
        Object task = scenario.get("latestTask");
        if (!(task instanceof Map<?, ?> taskMap) || taskMap.get("id") == null) {
            return List.of();
        }
        long taskId = ((Number) taskMap.get("id")).longValue();
        return jdbcTemplate.query(
                "SELECT * FROM transfer_simulation_point WHERE task_id=? AND time_point=(SELECT MAX(time_point) "
                        + "FROM transfer_simulation_point WHERE task_id=?) ORDER BY entity_type, entity_code",
                this::pointRow,
                taskId,
                taskId);
    }

    private String firstLoadBlock(Map<String, Object> scenario) {
        Object blocks = scenario.get("loadBlocks");
        if (blocks instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0));
        }
        return "";
    }

    private Map<String, Object> requireReadable(long id) {
        List<Map<String, Object>> rows =
                jdbcTemplate.query("SELECT * FROM grid_control_session WHERE id=?", this::sessionRow, id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("仿真调控会话不存在");
        }
        Map<String, Object> row = rows.get(0);
        planningService.planDetail(((Number) row.get("planId")).longValue());
        return row;
    }

    private Map<String, Object> requireOwned(long id) {
        Map<String, Object> row = requireReadable(id);
        LoginUser user = UserContext.get();
        if (!"ADMIN".equals(user.getRoleCode()) && ((Number) row.get("createdBy")).longValue() != user.getUserId()) {
            throw new AccessDeniedException("只能操作自己创建的仿真调控会话");
        }
        return row;
    }

    private Map<String, Object> stateSnapshot(Map<String, Object> session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", session.get("status"));
        result.put("sourceSwitchState", session.get("sourceSwitchState"));
        result.put("tieSwitchState", session.get("tieSwitchState"));
        result.put("supplyState", session.get("supplyState"));
        result.put("transferred", session.get("transferred"));
        result.put("verified", session.get("verified"));
        return result;
    }

    private Map<String, Object> sessionRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("planId", rs.getLong("plan_id"));
        row.put("scenarioId", rs.getLong("scenario_id"));
        row.put("createdBy", rs.getLong("created_by"));
        row.put("sessionName", rs.getString("session_name"));
        row.put("status", rs.getString("status"));
        row.put("currentStepNo", rs.getInt("current_step_no"));
        row.put("sourceSwitchState", rs.getString("source_switch_state"));
        row.put("tieSwitchState", rs.getString("tie_switch_state"));
        row.put("supplyState", rs.getString("supply_state"));
        row.put("transferred", rs.getBoolean("transferred"));
        row.put("verified", rs.getBoolean("verified"));
        row.put("version", rs.getLong("version"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        Timestamp completed = rs.getTimestamp("completed_at");
        row.put("completedAt", completed == null ? null : completed.toLocalDateTime());
        return row;
    }

    private Map<String, Object> actionRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("sequenceNo", rs.getInt("sequence_no"));
        row.put("actionType", rs.getString("action_type"));
        row.put("deviceCode", rs.getString("device_code"));
        row.put("status", rs.getString("status"));
        row.put("instruction", rs.getString("instruction"));
        row.put("beforeStateJson", rs.getString("before_state_json"));
        row.put("afterStateJson", rs.getString("after_state_json"));
        row.put("resultJson", rs.getString("result_json"));
        row.put("operatorName", rs.getString("operator_name"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> pointRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("taskId", rs.getLong("task_id"));
        row.put("timePoint", rs.getTimestamp("time_point").toLocalDateTime());
        row.put("entityType", rs.getString("entity_type"));
        row.put("entityCode", rs.getString("entity_code"));
        row.put("beforeLoadKw", rs.getBigDecimal("before_load_kw"));
        row.put("transferLoadKw", rs.getBigDecimal("transfer_load_kw"));
        row.put("afterLoadKw", rs.getBigDecimal("after_load_kw"));
        row.put("capacityKw", rs.getBigDecimal("capacity_kw"));
        row.put("loadRate", rs.getBigDecimal("load_rate"));
        row.put("riskStatus", rs.getString("risk_status"));
        return row;
    }

    private void audit(long userId, String action, long resourceId, Map<String, Object> detail) {
        jdbcTemplate.update(
                "INSERT INTO audit_log(user_id, action, resource_type, resource_id, result, detail_json) "
                        + "VALUES (?, ?, 'GRID_CONTROL_SESSION', ?, 'SUCCESS', ?)",
                userId,
                action,
                String.valueOf(resourceId),
                json(detail));
    }

    private String resultMessage(String action) {
        return switch (action) {
            case "CONFIRM" -> "演练已开始，系统已锁定推荐方案";
            case "OPEN_SOURCE" -> "原供电线路已在仿真中断开，异常设备已隔离";
            case "CLOSE_TIE" -> "备用线路已在仿真中接通";
            case "TRANSFER" -> "负荷已转移，拓扑和负载值已刷新";
            case "VERIFY" -> "调控后状态核验通过，本次演练完成";
            default -> "操作完成";
        };
    }

    private String label(String action) {
        return switch (action) {
            case "CONFIRM" -> "确认并开始";
            case "OPEN_SOURCE" -> "断开原线路";
            case "CLOSE_TIE" -> "接通备用线路";
            case "TRANSFER" -> "转移负荷";
            case "VERIFY" -> "核验结果";
            default -> action;
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("调控状态序列化失败", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private record State(
            String status,
            String sourceSwitch,
            String tieSwitch,
            String supply,
            boolean transferred,
            boolean verified) {}
}
