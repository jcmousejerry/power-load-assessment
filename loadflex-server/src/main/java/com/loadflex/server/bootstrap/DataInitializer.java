package com.loadflex.server.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.loadflex.common.entity.AnalysisTask;
import com.loadflex.common.entity.AppUser;
import com.loadflex.common.entity.Dataset;
import com.loadflex.common.entity.GridTransformer;
import com.loadflex.common.entity.GridUserTransformerMonitor;
import com.loadflex.common.mapper.AnalysisTaskMapper;
import com.loadflex.common.mapper.AppUserMapper;
import com.loadflex.common.mapper.DatasetMapper;
import com.loadflex.common.mapper.GridTransformerMapper;
import com.loadflex.common.mapper.GridUserTransformerMonitorMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {
    private final AppUserMapper userMapper;
    private final PasswordEncoder encoder;
    private final AnalysisTaskMapper taskMapper;
    private final DatasetMapper datasetMapper;
    private final GridTransformerMapper transformerMapper;
    private final GridUserTransformerMonitorMapper monitorMapper;
    private final JdbcTemplate jdbcTemplate;

    public DataInitializer(
            AppUserMapper userMapper,
            PasswordEncoder encoder,
            AnalysisTaskMapper taskMapper,
            DatasetMapper datasetMapper,
            GridTransformerMapper transformerMapper,
            GridUserTransformerMonitorMapper monitorMapper,
            JdbcTemplate jdbcTemplate) {
        this.userMapper = userMapper;
        this.encoder = encoder;
        this.taskMapper = taskMapper;
        this.datasetMapper = datasetMapper;
        this.transformerMapper = transformerMapper;
        this.monitorMapper = monitorMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        List<Long> newUserIds = new ArrayList<>();
        ensureUser("admin", "平台管理员", "123456", "ADMIN", newUserIds);
        ensureUser("analyst01", "负荷分析员一", "123456", "ANALYST", newUserIds);
        ensureUser("analyst02", "负荷分析员二", "123456", "ANALYST", newUserIds);
        ensureUser("viewer01", "结果查看员", "123456", "VIEWER", newUserIds);
        ensurePlanningTopology();
        ensureTransformer("T001", "东区一号变压器", "东区", "F01", "800", true);
        ensureTransformer("T002", "西区二号变压器", "西区", "F01", "1000", true);
        ensureTransformer("T003", "生产区三号变压器", "生产区", "F01", "1000", true);
        ensureTransformer("T004", "北区四号变压器", "北区", "F02", "500", false);
        ensureTransformer("T005", "南区五号变压器", "南区", "F02", "1250", false);
        ensureTransformer("T006", "仓储区六号变压器", "仓储区", "F02", "800", false);
        ensureTransformer("T007", "办公区七号变压器", "办公区", "F03", "400", false);
        ensureTransformer("T008", "研发区八号变压器", "研发区", "F03", "630", false);
        ensureTransformer("T009", "物流区九号变压器", "物流区", "F03", "1000", false);
        ensureTransformer("T010", "动力区十号变压器", "动力区", "F04", "1600", false);
        ensureTransformer("T011", "生活区十一号变压器", "生活区", "F04", "500", false);
        ensureTransformer("T012", "备用十二号变压器", "备用区", "F04", "800", false);
        newUserIds.forEach(this::ensureDefaultMonitoring);
        repairUnreadableTaskNames();
    }

    private void ensureTransformer(
            String code,
            String name,
            String area,
            String feederCode,
            String ratedCapacityKw,
            boolean monitoringEnabled) {
        GridTransformer transformer = transformerMapper.selectOne(
                new LambdaQueryWrapper<GridTransformer>().eq(GridTransformer::getTransformerCode, code));
        if (transformer != null) {
            transformer.setTransformerName(name);
            transformer.setAreaName(area);
            transformer.setFeederCode(feederCode);
            transformer.setRatedCapacityKw(new BigDecimal(ratedCapacityKw));
            transformerMapper.updateById(transformer);
            return;
        }
        transformer = new GridTransformer();
        transformer.setTransformerCode(code);
        transformer.setTransformerName(name);
        transformer.setAreaName(area);
        transformer.setFeederCode(feederCode);
        transformer.setRatedCapacityKw(new BigDecimal(ratedCapacityKw));
        transformer.setStatus("ACTIVE");
        transformer.setMonitoringEnabled(monitoringEnabled);
        transformerMapper.insert(transformer);
    }

    private void ensurePlanningTopology() {
        upsertFeeder("F01", "东部生产馈线", "2800", "110kV东部变电站");
        upsertFeeder("F02", "南部仓储馈线", "2700", "110kV南部变电站");
        upsertFeeder("F03", "研发办公馈线", "2300", "110kV科技园变电站");
        upsertFeeder("F04", "动力生活馈线", "3200", "110kV动力变电站");
        upsertSwitch("S-F01", "F01出线开关", "BREAKER", "F01", null, "CLOSED");
        upsertSwitch("S-F02", "F02出线开关", "BREAKER", "F02", null, "CLOSED");
        upsertSwitch("S-F03", "F03出线开关", "BREAKER", "F03", null, "CLOSED");
        upsertSwitch("S-F04", "F04出线开关", "BREAKER", "F04", null, "CLOSED");
        upsertSwitch("S-LINK-01", "F01-F02联络开关", "TIE", "F01", "F02", "OPEN");
        upsertSwitch("S-LINK-02", "F01-F03联络开关", "TIE", "F01", "F03", "OPEN");
        upsertSwitch("S-LINK-03", "F02-F04联络开关", "TIE", "F02", "F04", "OPEN");
        upsertSwitch("S-LINK-04", "F03-F04联络开关", "TIE", "F03", "F04", "OPEN");
    }

    private void upsertFeeder(String code, String name, String capacity, String sourceName) {
        jdbcTemplate.update(
                "INSERT INTO grid_feeder(feeder_code, feeder_name, rated_capacity_kw, source_name) "
                        + "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE feeder_name=VALUES(feeder_name), "
                        + "rated_capacity_kw=VALUES(rated_capacity_kw), source_name=VALUES(source_name)",
                code,
                name,
                new BigDecimal(capacity),
                sourceName);
    }

    private void upsertSwitch(
            String code, String name, String type, String fromFeeder, String toFeeder, String normalState) {
        jdbcTemplate.update(
                "INSERT INTO grid_switch(switch_code, switch_name, switch_type, from_feeder_code, "
                        + "to_feeder_code, normal_state) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                        + "switch_name=VALUES(switch_name), switch_type=VALUES(switch_type), "
                        + "from_feeder_code=VALUES(from_feeder_code), to_feeder_code=VALUES(to_feeder_code), "
                        + "normal_state=VALUES(normal_state)",
                code,
                name,
                type,
                fromFeeder,
                toFeeder,
                normalState);
    }

    private void ensureUser(
            String username, String displayName, String password, String roleCode, List<Long> newUserIds) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username));
        if (count > 0) {
            return;
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(encoder.encode(password));
        user.setRoleCode(roleCode);
        user.setStatus("ACTIVE");
        userMapper.insert(user);
        newUserIds.add(user.getId());
    }

    private void ensureDefaultMonitoring(Long userId) {
        for (String transformerCode : List.of("T001", "T002", "T003")) {
            GridUserTransformerMonitor monitor = new GridUserTransformerMonitor();
            monitor.setUserId(userId);
            monitor.setTransformerCode(transformerCode);
            monitorMapper.insert(monitor);
        }
        for (GridTransformer transformer : transformerMapper.selectList(new LambdaQueryWrapper<GridTransformer>()
                .in(GridTransformer::getTransformerCode, "T001", "T002", "T003"))) {
            if (!Boolean.TRUE.equals(transformer.getMonitoringEnabled())) {
                transformer.setMonitoringEnabled(true);
                transformerMapper.updateById(transformer);
            }
        }
    }

    private void repairUnreadableTaskNames() {
        Map<String, String> labels = Map.of(
                "PROFILE", "数据质量检查",
                "FEATURE", "用户特征提取",
                "CLUSTER", "用户聚类",
                "FORECAST", "集群负荷预测",
                "BASELINE", "集群基线",
                "POTENTIAL", "调节潜力评估");
        for (AnalysisTask task :
                taskMapper.selectList(new LambdaQueryWrapper<AnalysisTask>().like(AnalysisTask::getTaskName, "?"))) {
            Dataset dataset = datasetMapper.selectById(task.getDatasetId());
            String datasetName = dataset == null ? "数据集" + task.getDatasetId() : dataset.getName();
            task.setTaskName(datasetName + " - " + labels.getOrDefault(task.getTaskType(), "分析任务"));
            taskMapper.updateById(task);
        }
    }
}
