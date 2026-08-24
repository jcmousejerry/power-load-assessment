package com.loadflex.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loadflex.server.security.AccessControlService;
import com.loadflex.server.security.LoginUser;
import com.loadflex.server.security.UserContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaintenancePlanningService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AccessControlService accessControlService;
    private final GridLoadProfileService profileService;
    private final ExecutorService simulationExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "transfer-simulation-worker");
        thread.setDaemon(true);
        return thread;
    });

    public MaintenancePlanningService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AccessControlService accessControlService,
            GridLoadProfileService profileService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.accessControlService = accessControlService;
        this.profileService = profileService;
    }

    public Map<String, Object> topology() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "feeders",
                jdbcTemplate.query(
                        "SELECT feeder_code, feeder_name, rated_capacity_kw, source_name, status "
                                + "FROM grid_feeder ORDER BY feeder_code",
                        this::feederRow));
        result.put(
                "switches",
                jdbcTemplate.query(
                        "SELECT switch_code, switch_name, switch_type, from_feeder_code, to_feeder_code, "
                                + "normal_state, status FROM grid_switch ORDER BY switch_code",
                        this::switchRow));
        result.put(
                "transformers",
                jdbcTemplate.query(
                        "SELECT transformer_code, transformer_name, area_name, feeder_code, rated_capacity_kw, status "
                                + "FROM grid_transformer ORDER BY transformer_code",
                        this::transformerRow));
        return result;
    }

    public Map<String, Object> agentDraftPreview(
            String transformerCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal safetyLimit,
            String profileType) {
        validatePlan(
                "Agent临时方案",
                "Agent临时校核",
                "TRANSFORMER",
                transformerCode,
                startTime,
                endTime,
                safetyLimit,
                profileType,
                null);
        String sourceFeeder = jdbcTemplate.queryForObject(
                "SELECT feeder_code FROM grid_transformer WHERE transformer_code=?", String.class, transformerCode);
        List<Map<String, Object>> transformers = jdbcTemplate.query(
                "SELECT transformer_code, transformer_name, area_name, feeder_code, rated_capacity_kw, status "
                        + "FROM grid_transformer WHERE status='ACTIVE' ORDER BY transformer_code",
                this::transformerRow);
        List<String> codes = transformers.stream()
                .map(row -> String.valueOf(row.get("transformerCode")))
                .collect(Collectors.toList());
        GridLoadProfileService.ProfileSet profiles = profileService.load(codes, startTime, profileType, null);
        Map<String, Double> capacities = transformers.stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("transformerCode")),
                        row -> ((BigDecimal) row.get("ratedCapacityKw")).doubleValue()));
        Map<String, String> transformerFeeders = transformers.stream()
                .collect(Collectors.toMap(
                        row -> String.valueOf(row.get("transformerCode")),
                        row -> String.valueOf(row.get("feederCode"))));
        Map<String, Double> feederCapacities =
                jdbcTemplate.query("SELECT feeder_code, rated_capacity_kw FROM grid_feeder", rs -> {
                    Map<String, Double> values = new LinkedHashMap<>();
                    while (rs.next()) {
                        values.put(rs.getString(1), rs.getDouble(2));
                    }
                    return values;
                });
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> link : adjacentFeeders(sourceFeeder)) {
            String targetFeeder = String.valueOf(link.get("targetFeederCode"));
            String targetTransformer = bestTargetTransformer(targetFeeder);
            double maxRate = 0;
            List<String> riskTimes = new ArrayList<>();
            LocalDateTime point = roundDownQuarter(startTime);
            while (!point.isAfter(endTime)) {
                double transferLoad = profiles.value(transformerCode, point);
                double targetBefore = profiles.value(targetTransformer, point);
                double transformerRate = (targetBefore + transferLoad) / capacities.get(targetTransformer);
                double feederBefore = 0;
                for (String code : codes) {
                    if (targetFeeder.equals(transformerFeeders.get(code))) {
                        feederBefore += profiles.value(code, point);
                    }
                }
                double feederRate = (feederBefore + transferLoad) / feederCapacities.get(targetFeeder);
                double pointMax = Math.max(transformerRate, feederRate);
                maxRate = Math.max(maxRate, pointMax);
                if (pointMax > safetyLimit.doubleValue()) {
                    riskTimes.add(point.toString());
                }
                point = point.plusMinutes(15);
            }
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("sourceFeederCode", sourceFeeder);
            candidate.put("targetFeederCode", targetFeeder);
            candidate.put("targetTransformerCode", targetTransformer);
            candidate.put("tieSwitchCode", link.get("switchCode"));
            candidate.put("maximumLoadRate", round(maxRate));
            candidate.put("maximumLoadPercent", round(maxRate * 100));
            candidate.put("riskTimes", riskTimes);
            candidate.put("feasible", riskTimes.isEmpty());
            candidate.put(
                    "steps",
                    List.of(
                            step("SAFETY_CHECK", null, "确认" + transformerCode + "检修许可与安全措施", "CONFIRMED"),
                            step("OPEN_SWITCH", "S-" + sourceFeeder, "断开" + sourceFeeder + "出线开关", "OPEN"),
                            step(
                                    "CLOSE_SWITCH",
                                    String.valueOf(link.get("switchCode")),
                                    "闭合" + link.get("switchCode") + "，接通" + targetFeeder,
                                    "CLOSED"),
                            step(
                                    "TRANSFER_LOAD",
                                    targetTransformer,
                                    "将" + transformerCode + "负荷转移至" + targetTransformer,
                                    "TRANSFERRED"),
                            step("SAFETY_CHECK", targetTransformer, "核对转供后负载率及相序", "CONFIRMED"),
                            step("RESTORE", String.valueOf(link.get("switchCode")), "检修结束后恢复原供电方式", "OPEN")));
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparing((Map<String, Object> item) -> !Boolean.TRUE.equals(item.get("feasible")))
                .thenComparingDouble(item -> ((Number) item.get("maximumLoadRate")).doubleValue()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transformerCode", transformerCode);
        result.put("sourceFeederCode", sourceFeeder);
        result.put("startTime", startTime);
        result.put("endTime", endTime);
        result.put("safetyLimit", safetyLimit);
        result.put("profileType", profileType);
        result.put("dataSource", profiles.dataSource());
        result.put("candidates", candidates);
        result.put("recommended", candidates.isEmpty() ? null : candidates.get(0));
        return result;
    }

    public Map<String, Object> agentTopologyContext(String transformerCode) {
        Map<String, Object> transformer = jdbcTemplate.queryForObject(
                "SELECT transformer_code, transformer_name, area_name, feeder_code, rated_capacity_kw, status "
                        + "FROM grid_transformer WHERE transformer_code=?",
                this::transformerRow,
                transformerCode);
        String sourceFeeder = String.valueOf(transformer.get("feederCode"));
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Map<String, Object> adjacent : adjacentFeeders(sourceFeeder)) {
            Map<String, Object> item = new LinkedHashMap<>(adjacent);
            String targetFeeder = String.valueOf(adjacent.get("targetFeederCode"));
            item.put("targetTransformerCode", bestTargetTransformer(targetFeeder));
            connections.add(item);
        }
        return Map.of(
                "transformer", transformer,
                "sourceFeederCode", sourceFeeder,
                "availableConnections", connections,
                "connectionCount", connections.size());
    }

    public List<Map<String, Object>> listPlans() {
        LoginUser user = UserContext.get();
        String sql = "SELECT p.*, u.display_name owner_name FROM maintenance_plan p "
                + "JOIN app_user u ON u.id=p.owner_id ";
        List<Object> args = new ArrayList<>();
        if ("ANALYST".equals(user.getRoleCode())) {
            sql += "WHERE p.owner_id=? ";
            args.add(user.getUserId());
        }
        sql += "ORDER BY p.updated_at DESC";
        return jdbcTemplate.query(sql, this::planRow, args.toArray());
    }

    public Map<String, Object> planDetail(long planId) {
        Map<String, Object> plan = requireReadablePlan(planId);
        Map<String, Object> result = new LinkedHashMap<>(plan);
        List<Map<String, Object>> scenarios = jdbcTemplate.query(
                "SELECT * FROM transfer_scenario WHERE plan_id=? ORDER BY version_no", this::scenarioRow, planId);
        for (Map<String, Object> scenario : scenarios) {
            long scenarioId = ((Number) scenario.get("id")).longValue();
            scenario.put("steps", steps(scenarioId));
            if (scenario.get("summaryJson") != null) {
                scenario.put("summary", parseMap(String.valueOf(scenario.get("summaryJson"))));
            }
            Long taskId = asLong(scenario.get("latestTaskId"));
            if (taskId != null) {
                scenario.put("latestTask", simulationTask(taskId));
            }
        }
        result.put("scenarios", scenarios);
        result.put(
                "reviews",
                jdbcTemplate.query(
                        "SELECT r.*, u.display_name operator_name FROM plan_review_record r "
                                + "JOIN app_user u ON u.id=r.operator_id WHERE r.plan_id=? ORDER BY r.id",
                        this::reviewRow,
                        planId));
        result.put("impact", impact(planId));
        return result;
    }

    @Transactional
    public void deletePlan(long planId) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> plan = requireReadablePlan(planId);
        LoginUser user = UserContext.get();
        if (!"ADMIN".equals(user.getRoleCode()) && ((Number) plan.get("ownerId")).longValue() != user.getUserId()) {
            throw new AccessDeniedException("只能删除自己创建的历史分析");
        }
        Integer activeTasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfer_simulation_task t JOIN transfer_scenario s ON s.id=t.scenario_id "
                        + "WHERE s.plan_id=? AND t.status IN ('QUEUED','PENDING','RUNNING','CANCEL_REQUESTED')",
                Integer.class,
                planId);
        if (activeTasks != null && activeTasks > 0) {
            throw new IllegalArgumentException("分析仍在运行，请等待完成或取消后再删除");
        }
        // linked_plan_id intentionally has no foreign key because an Agent session may outlive a plan.
        // Clear the link first so deleting a plan never leaves a stale UI navigation target.
        jdbcTemplate.update("UPDATE planning_agent_session SET linked_plan_id=NULL WHERE linked_plan_id=?", planId);
        jdbcTemplate.update("DELETE FROM maintenance_plan WHERE id=?", planId);
        audit(user.getUserId(), "DELETE", "MAINTENANCE_PLAN", planId, Map.of("planName", plan.get("planName")));
    }

    @Transactional
    public Map<String, Object> createPlan(
            String planName,
            String reason,
            String deviceType,
            String deviceCode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal safetyLimit,
            String profileType,
            LocalDate historicalDate,
            String responsiblePerson) {
        accessControlService.requireAnalysisPermission();
        validatePlan(
                planName, reason, deviceType, deviceCode, startTime, endTime, safetyLimit, profileType, historicalDate);
        LoginUser user = UserContext.get();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO maintenance_plan(owner_id, plan_name, reason, outage_device_type, "
                                    + "outage_device_code, start_time, end_time, safety_limit, load_profile_type, "
                                    + "historical_date, responsible_person, status) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')",
                            new String[] {"id"});
                    statement.setLong(1, user.getUserId());
                    statement.setString(2, planName.trim());
                    statement.setString(3, reason.trim());
                    statement.setString(4, deviceType);
                    statement.setString(5, deviceCode);
                    statement.setTimestamp(6, Timestamp.valueOf(startTime));
                    statement.setTimestamp(7, Timestamp.valueOf(endTime));
                    statement.setBigDecimal(8, safetyLimit);
                    statement.setString(9, profileType);
                    statement.setDate(10, historicalDate == null ? null : Date.valueOf(historicalDate));
                    statement.setString(11, trimToNull(responsiblePerson));
                    return statement;
                },
                holder);
        long planId = Objects.requireNonNull(holder.getKey()).longValue();
        audit(user.getUserId(), "CREATE", "MAINTENANCE_PLAN", planId, Map.of("planName", planName));
        return planDetail(planId);
    }

    @Transactional
    public Map<String, Object> updatePlan(
            long planId,
            String planName,
            String reason,
            LocalDateTime startTime,
            LocalDateTime endTime,
            BigDecimal safetyLimit,
            String profileType,
            LocalDate historicalDate,
            String responsiblePerson) {
        Map<String, Object> plan = requireEditablePlan(planId);
        validatePlan(
                planName,
                reason,
                String.valueOf(plan.get("outageDeviceType")),
                String.valueOf(plan.get("outageDeviceCode")),
                startTime,
                endTime,
                safetyLimit,
                profileType,
                historicalDate);
        jdbcTemplate.update(
                "UPDATE maintenance_plan SET plan_name=?, reason=?, start_time=?, end_time=?, safety_limit=?, "
                        + "load_profile_type=?, historical_date=?, responsible_person=?, version=version+1 "
                        + "WHERE id=?",
                planName.trim(),
                reason.trim(),
                Timestamp.valueOf(startTime),
                Timestamp.valueOf(endTime),
                safetyLimit,
                profileType,
                historicalDate == null ? null : Date.valueOf(historicalDate),
                trimToNull(responsiblePerson),
                planId);
        jdbcTemplate.update("UPDATE transfer_scenario SET status='STALE' WHERE plan_id=? AND status<>'DRAFT'", planId);
        return planDetail(planId);
    }

    public Map<String, Object> impact(long planId) {
        Map<String, Object> plan = requireReadablePlan(planId);
        List<String> impacted = impactedTransformers(plan);
        List<Map<String, Object>> transformerDetails = queryTransformers(impacted);
        BigDecimal ratedTotal = transformerDetails.stream()
                .map(row -> (BigDecimal) row.get("ratedCapacityKw"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDateTime start = (LocalDateTime) plan.get("startTime");
        GridLoadProfileService.ProfileSet profiles = profileService.load(
                impacted, start, String.valueOf(plan.get("loadProfileType")), (LocalDate) plan.get("historicalDate"));
        double estimatedLoad = impacted.stream()
                .mapToDouble(code -> profiles.value(code, start))
                .sum();
        String sourceFeeder = sourceFeeder(plan);
        List<Map<String, Object>> alternatives = adjacentFeeders(sourceFeeder);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceFeederCode", sourceFeeder);
        result.put("impactedTransformers", transformerDetails);
        result.put("impactedTransformerCodes", impacted);
        result.put("ratedCapacityKw", ratedTotal);
        result.put("estimatedTransferLoadKw", round(estimatedLoad));
        result.put("dataSource", profiles.dataSource());
        result.put("alternativeFeeders", alternatives);
        result.put("conflicts", conflicts(planId, plan));
        return result;
    }

    @Transactional
    public List<Map<String, Object>> generateCandidates(long planId) {
        requireEditablePlan(planId);
        Map<String, Object> plan = requireReadablePlan(planId);
        Map<String, Object> impact = impact(planId);
        String sourceFeeder = String.valueOf(impact.get("sourceFeederCode"));
        @SuppressWarnings("unchecked")
        List<String> loadBlocks = (List<String>) impact.get("impactedTransformerCodes");
        List<Map<String, Object>> links = adjacentFeeders(sourceFeeder);
        if (links.isEmpty()) {
            throw new IllegalArgumentException("检修设备所在馈线没有可用联络开关，无法生成转供方案");
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        int limit = Math.min(5, links.size());
        for (int index = 0; index < limit; index++) {
            Map<String, Object> link = links.get(index);
            String targetFeeder = String.valueOf(link.get("targetFeederCode"));
            String targetTransformer = bestTargetTransformer(targetFeeder);
            int version = nextScenarioVersion(planId);
            long scenarioId = insertScenario(
                    planId,
                    "方案" + version + " · 经" + link.get("switchCode") + "转入" + targetFeeder,
                    version,
                    sourceFeeder,
                    targetFeeder,
                    targetTransformer,
                    String.valueOf(link.get("switchCode")),
                    loadBlocks,
                    UserContext.get().getUserId());
            insertDefaultSteps(scenarioId, plan, sourceFeeder, targetFeeder, targetTransformer, link, loadBlocks);
            candidates.add(scenarioDetail(scenarioId));
        }
        return candidates;
    }

    @Transactional
    public Map<String, Object> cloneScenario(long scenarioId) {
        Map<String, Object> source = requireEditableScenario(scenarioId);
        long planId = ((Number) source.get("planId")).longValue();
        int version = nextScenarioVersion(planId);
        List<String> blocks = parseStringList(String.valueOf(source.get("loadBlocksJson")));
        long cloneId = insertScenario(
                planId,
                "方案" + version + " · 复制自方案" + source.get("versionNo"),
                version,
                String.valueOf(source.get("sourceFeederCode")),
                String.valueOf(source.get("targetFeederCode")),
                String.valueOf(source.get("targetTransformerCode")),
                String.valueOf(source.get("tieSwitchCode")),
                blocks,
                UserContext.get().getUserId());
        for (Map<String, Object> step : steps(scenarioId)) {
            jdbcTemplate.update(
                    "INSERT INTO transfer_operation_step(scenario_id, step_no, step_type, device_code, "
                            + "instruction, expected_state) VALUES (?, ?, ?, ?, ?, ?)",
                    cloneId,
                    step.get("stepNo"),
                    step.get("stepType"),
                    step.get("deviceCode"),
                    step.get("instruction"),
                    step.get("expectedState"));
        }
        return scenarioDetail(cloneId);
    }

    @Transactional
    public Map<String, Object> saveSteps(long scenarioId, List<Map<String, String>> requestedSteps) {
        requireEditableScenario(scenarioId);
        if (requestedSteps == null || requestedSteps.isEmpty()) {
            throw new IllegalArgumentException("方案至少需要一个操作步骤");
        }
        jdbcTemplate.update("DELETE FROM transfer_operation_step WHERE scenario_id=?", scenarioId);
        int stepNo = 1;
        for (Map<String, String> step : requestedSteps) {
            String type = step.get("stepType");
            String instruction = step.get("instruction");
            if (type == null || instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("每个操作步骤都需要类型和操作说明");
            }
            jdbcTemplate.update(
                    "INSERT INTO transfer_operation_step(scenario_id, step_no, step_type, device_code, "
                            + "instruction, expected_state) VALUES (?, ?, ?, ?, ?, ?)",
                    scenarioId,
                    stepNo++,
                    type,
                    trimToNull(step.get("deviceCode")),
                    instruction.trim(),
                    trimToNull(step.get("expectedState")));
        }
        jdbcTemplate.update("UPDATE transfer_scenario SET status='DRAFT', summary_json=NULL WHERE id=?", scenarioId);
        return scenarioDetail(scenarioId);
    }

    @Transactional
    public Map<String, Object> startSimulation(long scenarioId) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> scenario = requireEditableScenario(scenarioId);
        long userId = UserContext.get().getUserId();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO transfer_simulation_task(scenario_id, requested_by, status, progress, stage) "
                                    + "VALUES (?, ?, 'QUEUED', 0, '等待计算资源')",
                            new String[] {"id"});
                    statement.setLong(1, scenarioId);
                    statement.setLong(2, userId);
                    return statement;
                },
                holder);
        long taskId = Objects.requireNonNull(holder.getKey()).longValue();
        jdbcTemplate.update(
                "UPDATE transfer_scenario SET latest_task_id=?, status='SIMULATING' WHERE id=?", taskId, scenarioId);
        jdbcTemplate.update(
                "UPDATE maintenance_plan SET status='SIMULATING' WHERE id=?",
                ((Number) scenario.get("planId")).longValue());
        CompletableFuture.runAsync(() -> executeSimulation(taskId, scenarioId), simulationExecutor);
        return simulationTask(taskId);
    }

    @Transactional
    public Map<String, Object> cancelSimulation(long taskId) {
        Map<String, Object> task = simulationTask(taskId);
        String status = String.valueOf(task.get("status"));
        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
            return task;
        }
        accessControlService.requireAnalysisPermission();
        jdbcTemplate.update(
                "UPDATE transfer_simulation_task SET status='CANCEL_REQUESTED', stage='正在安全取消' WHERE id=?", taskId);
        return simulationTask(taskId);
    }

    public Map<String, Object> simulationTask(long taskId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.query("SELECT * FROM transfer_simulation_task WHERE id=?", this::taskRow, taskId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("仿真任务不存在：" + taskId);
        }
        Map<String, Object> task = rows.get(0);
        long scenarioId = ((Number) task.get("scenarioId")).longValue();
        requireReadableScenario(scenarioId);
        return task;
    }

    public List<Map<String, Object>> simulationPoints(long taskId) {
        simulationTask(taskId);
        return jdbcTemplate.query(
                "SELECT * FROM transfer_simulation_point WHERE task_id=? ORDER BY time_point, entity_type, entity_code",
                this::pointRow,
                taskId);
    }

    public List<Map<String, Object>> compareScenarios(List<Long> scenarioIds) {
        if (scenarioIds == null || scenarioIds.size() < 2 || scenarioIds.size() > 3) {
            throw new IllegalArgumentException("请选择2到3个方案进行比较");
        }
        return scenarioIds.stream().map(this::scenarioDetail).collect(Collectors.toList());
    }

    @Transactional
    public Map<String, Object> submit(long planId, long scenarioId, String comment) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> plan = requireEditablePlan(planId);
        Map<String, Object> scenario = requireReadableScenario(scenarioId);
        if (((Number) scenario.get("planId")).longValue() != planId) {
            throw new IllegalArgumentException("选择的方案不属于当前检修计划");
        }
        if (!"SUCCEEDED".equals(scenario.get("status"))) {
            throw new IllegalArgumentException("只有仿真完成的方案才能提交审核");
        }
        Map<String, Object> summary = parseMap((String) scenario.get("summaryJson"));
        if (!Boolean.TRUE.equals(summary.get("feasible"))) {
            throw new IllegalArgumentException("方案仍存在超限、缺供或冲突，不能提交审核");
        }
        long userId = UserContext.get().getUserId();
        jdbcTemplate.update(
                "UPDATE maintenance_plan SET selected_scenario_id=?, status='UNDER_REVIEW' WHERE id=?",
                scenarioId,
                planId);
        jdbcTemplate.update(
                "INSERT INTO plan_review_record(plan_id, action, operator_id, comment_text) VALUES (?, 'SUBMIT', ?, ?)",
                planId,
                userId,
                trimToNull(comment));
        audit(userId, "SUBMIT", "MAINTENANCE_PLAN", planId, Map.of("scenarioId", scenarioId));
        return planDetail(planId);
    }

    @Transactional
    public Map<String, Object> review(long planId, String decision, String comment) {
        LoginUser user = UserContext.get();
        if (!"ADMIN".equals(user.getRoleCode())) {
            throw new AccessDeniedException("只有管理员可以审核检修计划");
        }
        Map<String, Object> plan = requireReadablePlan(planId);
        if (!"UNDER_REVIEW".equals(plan.get("status"))) {
            throw new IllegalArgumentException("当前计划不处于待审核状态");
        }
        if (((Number) plan.get("ownerId")).longValue() == user.getUserId()) {
            throw new AccessDeniedException("计划创建人不能审核自己的计划");
        }
        if (!Set.of("APPROVE", "REJECT").contains(decision)) {
            throw new IllegalArgumentException("审核结果只能是APPROVE或REJECT");
        }
        if ("REJECT".equals(decision) && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException("驳回计划时必须填写原因");
        }
        String status = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        jdbcTemplate.update("UPDATE maintenance_plan SET status=? WHERE id=?", status, planId);
        jdbcTemplate.update(
                "INSERT INTO plan_review_record(plan_id, action, operator_id, comment_text) VALUES (?, ?, ?, ?)",
                planId,
                decision,
                user.getUserId(),
                trimToNull(comment));
        audit(user.getUserId(), decision, "MAINTENANCE_PLAN", planId, Map.of("status", status));
        return planDetail(planId);
    }

    @Transactional
    public Map<String, Object> archive(long planId) {
        Map<String, Object> plan = requireEditablePlan(planId);
        if ("SIMULATING".equals(plan.get("status")) || "UNDER_REVIEW".equals(plan.get("status"))) {
            throw new IllegalArgumentException("仿真中或审核中的计划不能归档");
        }
        jdbcTemplate.update("UPDATE maintenance_plan SET status='ARCHIVED' WHERE id=?", planId);
        audit(UserContext.get().getUserId(), "ARCHIVE", "MAINTENANCE_PLAN", planId, Map.of());
        return planDetail(planId);
    }

    public Map<String, Object> scenarioDetail(long scenarioId) {
        Map<String, Object> scenario = requireReadableScenario(scenarioId);
        Map<String, Object> result = new LinkedHashMap<>(scenario);
        result.put("loadBlocks", parseStringList(String.valueOf(scenario.get("loadBlocksJson"))));
        result.put("steps", steps(scenarioId));
        Long taskId = asLong(scenario.get("latestTaskId"));
        if (taskId != null) {
            result.put("latestTask", simulationTask(taskId));
        }
        if (scenario.get("summaryJson") != null) {
            result.put("summary", parseMap(String.valueOf(scenario.get("summaryJson"))));
        }
        return result;
    }

    private void executeSimulation(long taskId, long scenarioId) {
        try {
            jdbcTemplate.update(
                    "UPDATE transfer_simulation_task SET status='RUNNING', progress=5, stage='读取拓扑与历史负荷', "
                            + "started_at=NOW(3) WHERE id=?",
                    taskId);
            Map<String, Object> scenario = rawScenario(scenarioId);
            Map<String, Object> plan = rawPlan(((Number) scenario.get("planId")).longValue());
            List<Map<String, Object>> transformers = jdbcTemplate.query(
                    "SELECT transformer_code, transformer_name, area_name, feeder_code, rated_capacity_kw, status "
                            + "FROM grid_transformer WHERE status='ACTIVE' ORDER BY transformer_code",
                    this::transformerRow);
            List<String> allCodes = transformers.stream()
                    .map(row -> String.valueOf(row.get("transformerCode")))
                    .collect(Collectors.toList());
            GridLoadProfileService.ProfileSet profiles = profileService.load(
                    allCodes,
                    (LocalDateTime) plan.get("startTime"),
                    String.valueOf(plan.get("loadProfileType")),
                    (LocalDate) plan.get("historicalDate"));
            jdbcTemplate.update(
                    "UPDATE transfer_simulation_task SET data_source=?, progress=15, stage='生成15分钟仿真时间片' WHERE id=?",
                    profiles.dataSource(),
                    taskId);
            jdbcTemplate.update("DELETE FROM transfer_simulation_point WHERE task_id=?", taskId);
            List<String> loadBlocks = parseStringList(String.valueOf(scenario.get("loadBlocksJson")));
            LocalDateTime start = roundDownQuarter((LocalDateTime) plan.get("startTime"));
            LocalDateTime end = (LocalDateTime) plan.get("endTime");
            long slotCount = Math.max(1, ChronoUnit.MINUTES.between(start, end) / 15);
            if (slotCount > 192) {
                throw new IllegalArgumentException("单次仿真最长支持48小时");
            }
            String sourceFeeder = String.valueOf(scenario.get("sourceFeederCode"));
            String targetFeeder = String.valueOf(scenario.get("targetFeederCode"));
            String targetTransformer = String.valueOf(scenario.get("targetTransformerCode"));
            double safetyLimit = ((BigDecimal) plan.get("safetyLimit")).doubleValue();
            Map<String, Double> capacities = transformers.stream()
                    .collect(Collectors.toMap(
                            row -> String.valueOf(row.get("transformerCode")),
                            row -> ((BigDecimal) row.get("ratedCapacityKw")).doubleValue()));
            Map<String, String> transformerFeeders = transformers.stream()
                    .collect(Collectors.toMap(
                            row -> String.valueOf(row.get("transformerCode")),
                            row -> String.valueOf(row.get("feederCode"))));
            Map<String, Double> feederCapacities =
                    jdbcTemplate.query("SELECT feeder_code, rated_capacity_kw FROM grid_feeder", rs -> {
                        Map<String, Double> values = new LinkedHashMap<>();
                        while (rs.next()) {
                            values.put(rs.getString(1), rs.getDouble(2));
                        }
                        return values;
                    });
            double maxRate = 0;
            double networkMaxRate = 0;
            int violationCount = 0;
            int backgroundViolationCount = 0;
            double unservedEnergy = 0;
            Set<String> riskEquipment = new LinkedHashSet<>();
            Set<String> backgroundRiskEquipment = new LinkedHashSet<>();
            for (int slotIndex = 0; slotIndex <= slotCount; slotIndex++) {
                if (isCancellationRequested(taskId)) {
                    markCancelled(taskId, scenarioId, ((Number) scenario.get("planId")).longValue());
                    return;
                }
                LocalDateTime time = start.plusMinutes(slotIndex * 15L);
                Map<String, Double> before = new LinkedHashMap<>();
                for (String code : allCodes) {
                    before.put(code, profiles.value(code, time));
                }
                double transferred = loadBlocks.stream()
                        .mapToDouble(code -> before.getOrDefault(code, 0.0))
                        .sum();
                Map<String, Double> after = new LinkedHashMap<>(before);
                for (String code : loadBlocks) {
                    after.put(code, 0.0);
                }
                after.put(targetTransformer, after.getOrDefault(targetTransformer, 0.0) + transferred);
                for (String code : allCodes) {
                    double capacity = capacities.get(code);
                    double transfer = after.get(code) - before.get(code);
                    double rate = capacity <= 0 ? 0 : after.get(code) / capacity;
                    String risk = risk(rate, safetyLimit);
                    insertPoint(
                            taskId,
                            time,
                            "TRANSFORMER",
                            code,
                            before.get(code),
                            transfer,
                            after.get(code),
                            capacity,
                            rate,
                            risk);
                    networkMaxRate = Math.max(networkMaxRate, rate);
                    if (!"NORMAL".equals(risk)) {
                        if (targetTransformer.equals(code)) {
                            violationCount++;
                            riskEquipment.add(code);
                        } else {
                            backgroundViolationCount++;
                            backgroundRiskEquipment.add(code);
                        }
                    }
                    if (targetTransformer.equals(code)) {
                        maxRate = Math.max(maxRate, rate);
                    }
                    if (targetTransformer.equals(code) && rate > safetyLimit) {
                        unservedEnergy += Math.max(0, after.get(code) - capacity * safetyLimit) * 0.25;
                    }
                }
                Map<String, Double> feederBefore = aggregateFeeders(before, transformerFeeders);
                Map<String, Double> feederAfter = aggregateFeeders(after, transformerFeeders);
                for (String feeder : feederCapacities.keySet()) {
                    double capacity = feederCapacities.get(feeder);
                    double beforeLoad = feederBefore.getOrDefault(feeder, 0.0);
                    double afterLoad = feederAfter.getOrDefault(feeder, 0.0);
                    double rate = capacity <= 0 ? 0 : afterLoad / capacity;
                    String risk = risk(rate, safetyLimit);
                    insertPoint(
                            taskId,
                            time,
                            "FEEDER",
                            feeder,
                            beforeLoad,
                            afterLoad - beforeLoad,
                            afterLoad,
                            capacity,
                            rate,
                            risk);
                    networkMaxRate = Math.max(networkMaxRate, rate);
                    if (!"NORMAL".equals(risk)) {
                        if (targetFeeder.equals(feeder)) {
                            violationCount++;
                            riskEquipment.add(feeder);
                        } else {
                            backgroundViolationCount++;
                            backgroundRiskEquipment.add(feeder);
                        }
                    }
                    if (targetFeeder.equals(feeder)) {
                        maxRate = Math.max(maxRate, rate);
                    }
                }
                BigDecimal progress = BigDecimal.valueOf(15.0 + 75.0 * (slotIndex + 1) / (slotCount + 1))
                        .setScale(2, RoundingMode.HALF_UP);
                jdbcTemplate.update(
                        "UPDATE transfer_simulation_task SET progress=?, stage=? WHERE id=?",
                        progress,
                        "仿真时间片 " + (slotIndex + 1) + "/" + (slotCount + 1),
                        taskId);
            }
            int conflicts =
                    conflicts(((Number) plan.get("id")).longValue(), plan).size();
            int operationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM transfer_operation_step WHERE scenario_id=?", Integer.class, scenarioId);
            boolean feasible = violationCount == 0 && conflicts == 0 && unservedEnergy < 0.001;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("feasible", feasible);
            summary.put("maximumLoadRate", round(maxRate));
            summary.put("maximumLoadPercent", round(maxRate * 100));
            summary.put("networkMaximumLoadRate", round(networkMaxRate));
            summary.put("networkMaximumLoadPercent", round(networkMaxRate * 100));
            summary.put("violationPointCount", violationCount);
            summary.put("backgroundViolationPointCount", backgroundViolationCount);
            summary.put("unservedEnergyKwh", round(unservedEnergy));
            summary.put("operationStepCount", operationCount);
            summary.put("conflictCount", conflicts);
            summary.put("riskEquipment", new ArrayList<>(riskEquipment));
            summary.put("backgroundRiskEquipment", new ArrayList<>(backgroundRiskEquipment));
            summary.put("timePointCount", slotCount + 1);
            summary.put("dataSource", profiles.dataSource());
            summary.put("sourceFeederCode", sourceFeeder);
            summary.put("targetFeederCode", targetFeeder);
            summary.put("targetTransformerCode", targetTransformer);
            summary.put("tieSwitchCode", scenario.get("tieSwitchCode"));
            String summaryJson = writeJson(summary);
            jdbcTemplate.update(
                    "UPDATE transfer_scenario SET status='SUCCEEDED', summary_json=? WHERE id=?",
                    summaryJson,
                    scenarioId);
            jdbcTemplate.update(
                    "UPDATE transfer_simulation_task SET status='SUCCEEDED', progress=100, stage='仿真完成', "
                            + "finished_at=NOW(3) WHERE id=?",
                    taskId);
            jdbcTemplate.update(
                    "UPDATE maintenance_plan SET status='READY' WHERE id=? AND status='SIMULATING'", plan.get("id"));
        } catch (Exception exception) {
            String message = safeError(exception);
            jdbcTemplate.update(
                    "UPDATE transfer_simulation_task SET status='FAILED', stage='仿真失败', error_message=?, "
                            + "finished_at=NOW(3) WHERE id=?",
                    message,
                    taskId);
            jdbcTemplate.update("UPDATE transfer_scenario SET status='FAILED' WHERE id=?", scenarioId);
            jdbcTemplate.update(
                    "UPDATE maintenance_plan SET status='DRAFT' "
                            + "WHERE id=(SELECT plan_id FROM transfer_scenario WHERE id=?)",
                    scenarioId);
        }
    }

    private void insertPoint(
            long taskId,
            LocalDateTime time,
            String entityType,
            String entityCode,
            double before,
            double transfer,
            double after,
            double capacity,
            double rate,
            String risk) {
        jdbcTemplate.update(
                "INSERT INTO transfer_simulation_point(task_id, time_point, entity_type, entity_code, "
                        + "before_load_kw, transfer_load_kw, after_load_kw, capacity_kw, load_rate, risk_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                taskId,
                Timestamp.valueOf(time),
                entityType,
                entityCode,
                decimal(before),
                decimal(transfer),
                decimal(after),
                decimal(capacity),
                BigDecimal.valueOf(rate).setScale(4, RoundingMode.HALF_UP),
                risk);
    }

    private Map<String, Double> aggregateFeeders(Map<String, Double> values, Map<String, String> feeders) {
        Map<String, Double> result = new LinkedHashMap<>();
        values.forEach((code, load) -> result.merge(feeders.get(code), load, Double::sum));
        return result;
    }

    private String risk(double rate, double safetyLimit) {
        if (rate > 1.0) {
            return "OVERLOAD";
        }
        if (rate > safetyLimit) {
            return "WARNING";
        }
        return "NORMAL";
    }

    private boolean isCancellationRequested(long taskId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM transfer_simulation_task WHERE id=?", String.class, taskId);
        return "CANCEL_REQUESTED".equals(status);
    }

    private void markCancelled(long taskId, long scenarioId, long planId) {
        jdbcTemplate.update(
                "UPDATE transfer_simulation_task SET status='CANCELLED', stage='已取消', finished_at=NOW(3) WHERE id=?",
                taskId);
        jdbcTemplate.update("UPDATE transfer_scenario SET status='DRAFT' WHERE id=?", scenarioId);
        jdbcTemplate.update("UPDATE maintenance_plan SET status='DRAFT' WHERE id=?", planId);
    }

    private List<Map<String, Object>> conflicts(long planId, Map<String, Object> plan) {
        String currentReason = String.valueOf(plan.get("reason"));
        if (currentReason.contains("模拟设备暂时不可用") || currentReason.contains("AI异常处理助手")) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT id, plan_name, outage_device_code, start_time, end_time, status FROM maintenance_plan "
                        + "WHERE id<>? AND status IN ('READY','UNDER_REVIEW','APPROVED') "
                        + "AND reason NOT LIKE '%模拟设备暂时不可用%' AND reason NOT LIKE '%AI异常处理助手%' "
                        + "AND start_time<? AND end_time>? "
                        + "AND outage_device_code=? ORDER BY start_time",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("planId", rs.getLong("id"));
                    row.put("planName", rs.getString("plan_name"));
                    row.put("deviceCode", rs.getString("outage_device_code"));
                    row.put("startTime", rs.getTimestamp("start_time").toLocalDateTime());
                    row.put("endTime", rs.getTimestamp("end_time").toLocalDateTime());
                    row.put("status", rs.getString("status"));
                    return row;
                },
                planId,
                Timestamp.valueOf((LocalDateTime) plan.get("endTime")),
                Timestamp.valueOf((LocalDateTime) plan.get("startTime")),
                plan.get("outageDeviceCode"));
    }

    private List<Map<String, Object>> adjacentFeeders(String sourceFeeder) {
        return jdbcTemplate.query(
                "SELECT switch_code, switch_name, from_feeder_code, to_feeder_code FROM grid_switch "
                        + "WHERE switch_type='TIE' AND status='ACTIVE' AND (from_feeder_code=? OR to_feeder_code=?)",
                (rs, rowNum) -> {
                    String from = rs.getString("from_feeder_code");
                    String to = rs.getString("to_feeder_code");
                    String target = sourceFeeder.equals(from) ? to : from;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("switchCode", rs.getString("switch_code"));
                    row.put("switchName", rs.getString("switch_name"));
                    row.put("targetFeederCode", target);
                    row.put(
                            "targetFeederName",
                            jdbcTemplate.queryForObject(
                                    "SELECT feeder_name FROM grid_feeder WHERE feeder_code=?", String.class, target));
                    row.put(
                            "ratedCapacityKw",
                            jdbcTemplate.queryForObject(
                                    "SELECT rated_capacity_kw FROM grid_feeder WHERE feeder_code=?",
                                    BigDecimal.class,
                                    target));
                    return row;
                },
                sourceFeeder,
                sourceFeeder);
    }

    private String bestTargetTransformer(String feederCode) {
        List<String> rows = jdbcTemplate.query(
                "SELECT t.transformer_code FROM grid_transformer t LEFT JOIN grid_transformer_metric m "
                        + "ON m.transformer_code=t.transformer_code WHERE t.feeder_code=? AND t.status='ACTIVE' "
                        + "ORDER BY COALESCE(m.load_rate, 0) ASC, t.rated_capacity_kw DESC LIMIT 1",
                (rs, rowNum) -> rs.getString(1),
                feederCode);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("备用馈线" + feederCode + "没有可承载负荷的变压器");
        }
        return rows.get(0);
    }

    private long insertScenario(
            long planId,
            String name,
            int version,
            String sourceFeeder,
            String targetFeeder,
            String targetTransformer,
            String switchCode,
            List<String> loadBlocks,
            long userId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            "INSERT INTO transfer_scenario(plan_id, scenario_name, version_no, source_feeder_code, "
                                    + "target_feeder_code, target_transformer_code, tie_switch_code, load_blocks_json, "
                                    + "created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            new String[] {"id"});
                    statement.setLong(1, planId);
                    statement.setString(2, name);
                    statement.setInt(3, version);
                    statement.setString(4, sourceFeeder);
                    statement.setString(5, targetFeeder);
                    statement.setString(6, targetTransformer);
                    statement.setString(7, switchCode);
                    statement.setString(8, writeJson(loadBlocks));
                    statement.setLong(9, userId);
                    return statement;
                },
                holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private void insertDefaultSteps(
            long scenarioId,
            Map<String, Object> plan,
            String sourceFeeder,
            String targetFeeder,
            String targetTransformer,
            Map<String, Object> link,
            List<String> loadBlocks) {
        List<Map<String, String>> defaults = List.of(
                step("SAFETY_CHECK", null, "确认" + plan.get("outageDeviceCode") + "检修许可、现场人员和安全措施", "CONFIRMED"),
                step("OPEN_SWITCH", "S-" + sourceFeeder, "断开" + sourceFeeder + "出线开关，隔离检修设备", "OPEN"),
                step(
                        "CLOSE_SWITCH",
                        String.valueOf(link.get("switchCode")),
                        "闭合" + link.get("switchCode") + "，接通" + targetFeeder + "备用供电路径",
                        "CLOSED"),
                step(
                        "TRANSFER_LOAD",
                        targetTransformer,
                        "将" + String.join("、", loadBlocks) + "负荷转移至" + targetTransformer,
                        "TRANSFERRED"),
                step("SAFETY_CHECK", targetTransformer, "核对转供后负载率及相序，确认无越限", "CONFIRMED"),
                step("RESTORE", String.valueOf(link.get("switchCode")), "检修结束后恢复原供电方式并断开联络开关", "OPEN"));
        int index = 1;
        for (Map<String, String> item : defaults) {
            jdbcTemplate.update(
                    "INSERT INTO transfer_operation_step(scenario_id, step_no, step_type, device_code, "
                            + "instruction, expected_state) VALUES (?, ?, ?, ?, ?, ?)",
                    scenarioId,
                    index++,
                    item.get("stepType"),
                    item.get("deviceCode"),
                    item.get("instruction"),
                    item.get("expectedState"));
        }
    }

    private Map<String, String> step(String stepType, String deviceCode, String instruction, String expectedState) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("stepType", stepType);
        result.put("deviceCode", deviceCode);
        result.put("instruction", instruction);
        result.put("expectedState", expectedState);
        return result;
    }

    private int nextScenarioVersion(long planId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM transfer_scenario WHERE plan_id=?", Integer.class, planId);
        return (max == null ? 0 : max) + 1;
    }

    private List<Map<String, Object>> steps(long scenarioId) {
        return jdbcTemplate.query(
                "SELECT * FROM transfer_operation_step WHERE scenario_id=? ORDER BY step_no",
                this::stepRow,
                scenarioId);
    }

    private Map<String, Object> requireReadablePlan(long planId) {
        Map<String, Object> plan = rawPlan(planId);
        LoginUser user = UserContext.get();
        if ("ANALYST".equals(user.getRoleCode()) && ((Number) plan.get("ownerId")).longValue() != user.getUserId()) {
            throw new AccessDeniedException("只能访问自己创建的检修计划");
        }
        return plan;
    }

    private Map<String, Object> requireEditablePlan(long planId) {
        accessControlService.requireAnalysisPermission();
        Map<String, Object> plan = requireReadablePlan(planId);
        LoginUser user = UserContext.get();
        if (!"ADMIN".equals(user.getRoleCode()) && ((Number) plan.get("ownerId")).longValue() != user.getUserId()) {
            throw new AccessDeniedException("只能修改自己创建的检修计划");
        }
        if (Set.of("UNDER_REVIEW", "APPROVED", "ARCHIVED").contains(String.valueOf(plan.get("status")))) {
            throw new IllegalArgumentException("当前状态的计划不能修改");
        }
        return plan;
    }

    private Map<String, Object> requireReadableScenario(long scenarioId) {
        Map<String, Object> scenario = rawScenario(scenarioId);
        requireReadablePlan(((Number) scenario.get("planId")).longValue());
        return scenario;
    }

    private Map<String, Object> requireEditableScenario(long scenarioId) {
        Map<String, Object> scenario = rawScenario(scenarioId);
        requireEditablePlan(((Number) scenario.get("planId")).longValue());
        return scenario;
    }

    private Map<String, Object> rawPlan(long planId) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT p.*, u.display_name owner_name FROM maintenance_plan p "
                        + "JOIN app_user u ON u.id=p.owner_id WHERE p.id=?",
                this::planRow,
                planId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("检修计划不存在：" + planId);
        }
        return rows.get(0);
    }

    private Map<String, Object> rawScenario(long scenarioId) {
        List<Map<String, Object>> rows =
                jdbcTemplate.query("SELECT * FROM transfer_scenario WHERE id=?", this::scenarioRow, scenarioId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("转供方案不存在：" + scenarioId);
        }
        return rows.get(0);
    }

    private String sourceFeeder(Map<String, Object> plan) {
        String type = String.valueOf(plan.get("outageDeviceType"));
        String code = String.valueOf(plan.get("outageDeviceCode"));
        if ("FEEDER".equals(type)) {
            return code;
        }
        if ("SWITCH".equals(type)) {
            return jdbcTemplate.queryForObject(
                    "SELECT from_feeder_code FROM grid_switch WHERE switch_code=?", String.class, code);
        }
        return jdbcTemplate.queryForObject(
                "SELECT feeder_code FROM grid_transformer WHERE transformer_code=?", String.class, code);
    }

    private List<String> impactedTransformers(Map<String, Object> plan) {
        String type = String.valueOf(plan.get("outageDeviceType"));
        String code = String.valueOf(plan.get("outageDeviceCode"));
        if ("TRANSFORMER".equals(type)) {
            return List.of(code);
        }
        String feeder = sourceFeeder(plan);
        return jdbcTemplate.query(
                "SELECT transformer_code FROM grid_transformer WHERE feeder_code=? AND status='ACTIVE' "
                        + "ORDER BY transformer_code",
                (rs, rowNum) -> rs.getString(1),
                feeder);
    }

    private List<Map<String, Object>> queryTransformers(List<String> codes) {
        if (codes.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        return jdbcTemplate.query(
                "SELECT transformer_code, transformer_name, area_name, feeder_code, rated_capacity_kw, status "
                        + "FROM grid_transformer WHERE transformer_code IN (" + placeholders
                        + ") ORDER BY transformer_code",
                this::transformerRow,
                codes.toArray());
    }

    private void validatePlan(
            String planName,
            String reason,
            String deviceType,
            String deviceCode,
            LocalDateTime start,
            LocalDateTime end,
            BigDecimal safetyLimit,
            String profileType,
            LocalDate historicalDate) {
        if (planName == null || planName.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("计划名称和检修原因不能为空");
        }
        if (!Set.of("TRANSFORMER", "FEEDER", "SWITCH").contains(deviceType)) {
            throw new IllegalArgumentException("不支持的停运设备类型");
        }
        if (deviceCode == null || deviceCode.isBlank() || !deviceExists(deviceType, deviceCode)) {
            throw new IllegalArgumentException("停运设备不存在：" + deviceCode);
        }
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("检修结束时间必须晚于开始时间");
        }
        if (Duration.between(start, end).toHours() > 48) {
            throw new IllegalArgumentException("首版单张检修计划最长支持48小时");
        }
        if (safetyLimit == null
                || safetyLimit.compareTo(BigDecimal.valueOf(0.5)) < 0
                || safetyLimit.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("容量安全线必须在50%到100%之间");
        }
        if (!Set.of("P50", "P90", "HISTORICAL_DAY").contains(profileType)) {
            throw new IllegalArgumentException("负荷口径只能是P50、P90或HISTORICAL_DAY");
        }
        if ("HISTORICAL_DAY".equals(profileType) && historicalDate == null) {
            throw new IllegalArgumentException("指定历史日负荷时必须选择日期");
        }
    }

    private boolean deviceExists(String type, String code) {
        String table;
        String column;
        switch (type) {
            case "TRANSFORMER":
                table = "grid_transformer";
                column = "transformer_code";
                break;
            case "FEEDER":
                table = "grid_feeder";
                column = "feeder_code";
                break;
            default:
                table = "grid_switch";
                column = "switch_code";
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Integer.class, code);
        return count != null && count > 0;
    }

    private void audit(long userId, String action, String resourceType, long resourceId, Map<String, Object> detail) {
        jdbcTemplate.update(
                "INSERT INTO audit_log(user_id, action, resource_type, resource_id, result, detail_json) "
                        + "VALUES (?, ?, ?, ?, 'SUCCESS', ?)",
                userId,
                action,
                resourceType,
                String.valueOf(resourceId),
                writeJson(detail));
    }

    private Map<String, Object> feederRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("feederCode", rs.getString("feeder_code"));
        row.put("feederName", rs.getString("feeder_name"));
        row.put("ratedCapacityKw", rs.getBigDecimal("rated_capacity_kw"));
        row.put("sourceName", rs.getString("source_name"));
        row.put("status", rs.getString("status"));
        return row;
    }

    private Map<String, Object> switchRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("switchCode", rs.getString("switch_code"));
        row.put("switchName", rs.getString("switch_name"));
        row.put("switchType", rs.getString("switch_type"));
        row.put("fromFeederCode", rs.getString("from_feeder_code"));
        row.put("toFeederCode", rs.getString("to_feeder_code"));
        row.put("normalState", rs.getString("normal_state"));
        row.put("status", rs.getString("status"));
        return row;
    }

    private Map<String, Object> transformerRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transformerCode", rs.getString("transformer_code"));
        row.put("transformerName", rs.getString("transformer_name"));
        row.put("areaName", rs.getString("area_name"));
        row.put("feederCode", rs.getString("feeder_code"));
        row.put("ratedCapacityKw", rs.getBigDecimal("rated_capacity_kw"));
        row.put("status", rs.getString("status"));
        return row;
    }

    private Map<String, Object> planRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("ownerId", rs.getLong("owner_id"));
        row.put("ownerName", rs.getString("owner_name"));
        row.put("planName", rs.getString("plan_name"));
        row.put("reason", rs.getString("reason"));
        row.put("outageDeviceType", rs.getString("outage_device_type"));
        row.put("outageDeviceCode", rs.getString("outage_device_code"));
        row.put("startTime", rs.getTimestamp("start_time").toLocalDateTime());
        row.put("endTime", rs.getTimestamp("end_time").toLocalDateTime());
        row.put("safetyLimit", rs.getBigDecimal("safety_limit"));
        row.put("loadProfileType", rs.getString("load_profile_type"));
        Date historicalDate = rs.getDate("historical_date");
        row.put("historicalDate", historicalDate == null ? null : historicalDate.toLocalDate());
        row.put("responsiblePerson", rs.getString("responsible_person"));
        row.put("status", rs.getString("status"));
        row.put("selectedScenarioId", nullableLong(rs, "selected_scenario_id"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> scenarioRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("planId", rs.getLong("plan_id"));
        row.put("scenarioName", rs.getString("scenario_name"));
        row.put("versionNo", rs.getInt("version_no"));
        row.put("sourceFeederCode", rs.getString("source_feeder_code"));
        row.put("targetFeederCode", rs.getString("target_feeder_code"));
        row.put("targetTransformerCode", rs.getString("target_transformer_code"));
        row.put("tieSwitchCode", rs.getString("tie_switch_code"));
        row.put("loadBlocksJson", rs.getString("load_blocks_json"));
        row.put("status", rs.getString("status"));
        row.put("latestTaskId", nullableLong(rs, "latest_task_id"));
        row.put("summaryJson", rs.getString("summary_json"));
        row.put("createdBy", rs.getLong("created_by"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> stepRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("scenarioId", rs.getLong("scenario_id"));
        row.put("stepNo", rs.getInt("step_no"));
        row.put("stepType", rs.getString("step_type"));
        row.put("deviceCode", rs.getString("device_code"));
        row.put("instruction", rs.getString("instruction"));
        row.put("expectedState", rs.getString("expected_state"));
        return row;
    }

    private Map<String, Object> taskRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("scenarioId", rs.getLong("scenario_id"));
        row.put("requestedBy", rs.getLong("requested_by"));
        row.put("status", rs.getString("status"));
        row.put("progress", rs.getBigDecimal("progress"));
        row.put("stage", rs.getString("stage"));
        row.put("dataSource", rs.getString("data_source"));
        row.put("errorMessage", rs.getString("error_message"));
        row.put("startedAt", nullableDateTime(rs, "started_at"));
        row.put("finishedAt", nullableDateTime(rs, "finished_at"));
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

    private Map<String, Object> reviewRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("planId", rs.getLong("plan_id"));
        row.put("action", rs.getString("action"));
        row.put("operatorId", rs.getLong("operator_id"));
        row.put("operatorName", rs.getString("operator_name"));
        row.put("comment", rs.getString("comment_text"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime nullableDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private List<String> parseStringList(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("方案负荷块数据损坏", exception);
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("方案摘要数据损坏", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化业务数据", exception);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDateTime roundDownQuarter(LocalDateTime value) {
        return value.withMinute(value.getMinute() / 15 * 15).withSecond(0).withNano(0);
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "仿真执行失败";
        }
        return message.length() > 900 ? message.substring(0, 900) : message;
    }

    @PreDestroy
    public void shutdown() {
        simulationExecutor.shutdownNow();
    }
}
