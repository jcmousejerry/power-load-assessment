CREATE DATABASE IF NOT EXISTS `loadflex_hub`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `loadflex_hub`;

CREATE TABLE IF NOT EXISTS `app_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户主键',
  `username` VARCHAR(64) NOT NULL COMMENT '登录用户名',
  `password_hash` VARCHAR(100) NOT NULL COMMENT 'BCrypt密码哈希，不保存明文密码',
  `display_name` VARCHAR(100) NOT NULL COMMENT '用户显示名称',
  `role_code` VARCHAR(30) NOT NULL DEFAULT 'ANALYST' COMMENT '角色：ADMIN管理员、ANALYST分析人员、VIEWER只读人员',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE启用、LOCKED锁定、DISABLED停用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  KEY `idx_user_status` (`status`)
) ENGINE=InnoDB COMMENT='平台用户表';

CREATE TABLE IF NOT EXISTS `dataset` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '数据集主键',
  `owner_id` BIGINT NOT NULL COMMENT '上传用户主键',
  `name` VARCHAR(150) NOT NULL COMMENT '数据集名称',
  `original_filename` VARCHAR(255) NOT NULL COMMENT '用户上传时的原文件名',
  `object_key` VARCHAR(500) NOT NULL COMMENT '原文件在MinIO中的对象路径',
  `content_type` VARCHAR(100) DEFAULT NULL COMMENT '文件MIME类型',
  `file_size` BIGINT NOT NULL COMMENT '文件字节数',
  `sha256` CHAR(64) NOT NULL COMMENT '文件SHA-256校验值',
  `status` VARCHAR(30) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态：UPLOADED、PROFILING、READY、QUALITY_BLOCKED、DELETED',
  `mapping_json` JSON DEFAULT NULL COMMENT '用户确认的字段映射、单位和采样间隔配置',
  `profile_json` JSON DEFAULT NULL COMMENT '数据剖析和质量检查摘要',
  `start_time` DATETIME DEFAULT NULL COMMENT '数据最早时间',
  `end_time` DATETIME DEFAULT NULL COMMENT '数据最晚时间',
  `row_count` BIGINT DEFAULT NULL COMMENT '标准化后的数据行数',
  `user_count` INT DEFAULT NULL COMMENT '数据中的电力用户数量',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除、1已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_name` (`owner_id`, `name`, `deleted`),
  KEY `idx_dataset_owner_created` (`owner_id`, `created_at` DESC),
  CONSTRAINT `fk_dataset_owner` FOREIGN KEY (`owner_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='数据集及最新处理状态表';

CREATE TABLE IF NOT EXISTS `dataset_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '不可变数据快照主键',
  `dataset_id` BIGINT NOT NULL COMMENT '来源数据集主键',
  `version_no` INT NOT NULL COMMENT '数据集内快照版本号',
  `object_key` VARCHAR(500) NOT NULL COMMENT '标准化Parquet或CSV文件对象路径',
  `manifest_json` JSON NOT NULL COMMENT '字段、单位、采样率、行数和校验值清单',
  `sha256` CHAR(64) NOT NULL COMMENT '快照文件SHA-256校验值',
  `quality_score` DECIMAL(5,2) DEFAULT NULL COMMENT '数据质量综合分，范围0到100',
  `status` VARCHAR(20) NOT NULL DEFAULT 'READY' COMMENT '状态：BUILDING、READY、BLOCKED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dataset_snapshot_version` (`dataset_id`, `version_no`),
  KEY `idx_snapshot_dataset_created` (`dataset_id`, `created_at` DESC),
  CONSTRAINT `fk_snapshot_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`)
) ENGINE=InnoDB COMMENT='标准化后的不可变数据快照表';

CREATE TABLE IF NOT EXISTS `analysis_pipeline` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分析流水线主键',
  `user_id` BIGINT NOT NULL COMMENT '流水线创建用户主键',
  `dataset_id` BIGINT NOT NULL COMMENT '流水线使用的数据集主键',
  `pipeline_name` VARCHAR(150) NOT NULL COMMENT '用户可读的流水线名称',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_pipeline_dataset_created` (`dataset_id`, `created_at` DESC),
  KEY `idx_pipeline_user_created` (`user_id`, `created_at` DESC),
  CONSTRAINT `fk_pipeline_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`),
  CONSTRAINT `fk_pipeline_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`)
) ENGINE=InnoDB COMMENT='一次完整负荷分析流水线；用于隔离同一数据集的多次分析';

CREATE TABLE IF NOT EXISTS `analysis_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分析任务主键',
  `user_id` BIGINT NOT NULL COMMENT '任务创建用户主键',
  `dataset_id` BIGINT NOT NULL COMMENT '输入数据集主键',
  `pipeline_id` BIGINT DEFAULT NULL COMMENT '所属分析流水线；历史单任务可以为空',
  `snapshot_id` BIGINT DEFAULT NULL COMMENT '输入数据快照主键，可为空表示直接使用原文件',
  `task_type` VARCHAR(30) NOT NULL COMMENT '任务类型：PROFILE、FEATURE、CLUSTER、FORECAST、BASELINE、POTENTIAL',
  `task_name` VARCHAR(150) NOT NULL COMMENT '用户可读的任务名称',
  `status` VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED' COMMENT '状态：SUBMITTED、QUEUED、DISPATCHING、RUNNING、CANCEL_REQUESTED、RETRY_WAIT、SUCCEEDED、FAILED、CANCELLED、REJECTED',
  `stage` VARCHAR(50) DEFAULT NULL COMMENT '当前执行阶段',
  `progress` DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '执行进度百分比，范围0到100',
  `priority` INT NOT NULL DEFAULT 0 COMMENT '任务优先级，数值越大优先级越高',
  `resource_pool` VARCHAR(30) NOT NULL DEFAULT 'CPU_LIGHT' COMMENT '资源池：CPU_LIGHT、CPU_HIGH_MEM、GPU',
  `parameters_json` JSON DEFAULT NULL COMMENT '算法参数JSON',
  `algorithm_version` VARCHAR(50) NOT NULL DEFAULT '1.0.0' COMMENT '算法版本',
  `queue_position` INT DEFAULT NULL COMMENT '最近一次估算的排队位次',
  `eta_p50_at` DATETIME(3) DEFAULT NULL COMMENT '正常情况下预计完成时间',
  `eta_p90_at` DATETIME(3) DEFAULT NULL COMMENT '较慢情况下预计完成时间',
  `eta_confidence` VARCHAR(20) DEFAULT NULL COMMENT '预计时间可信程度：LOW、MEDIUM、HIGH',
  `current_attempt` INT NOT NULL DEFAULT 0 COMMENT '当前执行次数',
  `cancel_requested` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否收到取消请求',
  `error_code` VARCHAR(80) DEFAULT NULL COMMENT '稳定的错误分类编码',
  `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '适合用户查看的错误信息',
  `idempotency_key` VARCHAR(100) DEFAULT NULL COMMENT '防止重复创建任务的请求编号',
  `queued_at` DATETIME(3) DEFAULT NULL COMMENT '进入排队时间',
  `started_at` DATETIME(3) DEFAULT NULL COMMENT '开始执行时间',
  `finished_at` DATETIME(3) DEFAULT NULL COMMENT '结束时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT NOT NULL DEFAULT 0 COMMENT '并发更新版本号',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_idempotency` (`user_id`, `idempotency_key`),
  KEY `idx_task_owner_created` (`user_id`, `created_at` DESC),
  KEY `idx_task_pipeline_created` (`pipeline_id`, `created_at` DESC),
  KEY `idx_task_schedule` (`status`, `resource_pool`, `priority`, `queued_at`),
  CONSTRAINT `fk_task_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`),
  CONSTRAINT `fk_task_dataset` FOREIGN KEY (`dataset_id`) REFERENCES `dataset` (`id`),
  CONSTRAINT `fk_task_pipeline` FOREIGN KEY (`pipeline_id`) REFERENCES `analysis_pipeline` (`id`)
) ENGINE=InnoDB COMMENT='数据分析任务事实状态表';

-- 兼容已经创建过 analysis_task 的本地数据库。MySQL 是事实源，迁移只补列、索引和外键，不修改旧任务归属。
SET @pipeline_column_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_task' AND COLUMN_NAME = 'pipeline_id'
);
SET @pipeline_column_sql = IF(
  @pipeline_column_exists = 0,
  'ALTER TABLE analysis_task ADD COLUMN pipeline_id BIGINT NULL COMMENT ''所属分析流水线；历史单任务可以为空'' AFTER dataset_id',
  'SELECT 1'
);
PREPARE pipeline_column_statement FROM @pipeline_column_sql;
EXECUTE pipeline_column_statement;
DEALLOCATE PREPARE pipeline_column_statement;

SET @pipeline_index_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_task' AND INDEX_NAME = 'idx_task_pipeline_created'
);
SET @pipeline_index_sql = IF(
  @pipeline_index_exists = 0,
  'ALTER TABLE analysis_task ADD INDEX idx_task_pipeline_created (pipeline_id, created_at DESC)',
  'SELECT 1'
);
PREPARE pipeline_index_statement FROM @pipeline_index_sql;
EXECUTE pipeline_index_statement;
DEALLOCATE PREPARE pipeline_index_statement;

SET @pipeline_fk_exists = (
  SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'analysis_task' AND CONSTRAINT_NAME = 'fk_task_pipeline'
);
SET @pipeline_fk_sql = IF(
  @pipeline_fk_exists = 0,
  'ALTER TABLE analysis_task ADD CONSTRAINT fk_task_pipeline FOREIGN KEY (pipeline_id) REFERENCES analysis_pipeline(id)',
  'SELECT 1'
);
PREPARE pipeline_fk_statement FROM @pipeline_fk_sql;
EXECUTE pipeline_fk_statement;
DEALLOCATE PREPARE pipeline_fk_statement;

CREATE TABLE IF NOT EXISTS `task_attempt` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务单次执行主键',
  `task_id` BIGINT NOT NULL COMMENT '分析任务主键',
  `attempt_no` INT NOT NULL COMMENT '从1开始的执行次数',
  `worker_id` VARCHAR(100) DEFAULT NULL COMMENT '执行该任务的Python Worker标识',
  `lease_id` VARCHAR(64) DEFAULT NULL COMMENT '本次执行许可唯一编号',
  `status` VARCHAR(30) NOT NULL COMMENT '本次执行状态',
  `started_at` DATETIME(3) DEFAULT NULL COMMENT '本次开始时间',
  `last_heartbeat_at` DATETIME(3) DEFAULT NULL COMMENT '最后心跳时间',
  `finished_at` DATETIME(3) DEFAULT NULL COMMENT '本次结束时间',
  `runtime_seconds` INT DEFAULT NULL COMMENT '本次实际运行秒数',
  `error_code` VARCHAR(80) DEFAULT NULL COMMENT '错误分类编码',
  `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_attempt` (`task_id`, `attempt_no`),
  KEY `idx_attempt_heartbeat` (`status`, `last_heartbeat_at`),
  CONSTRAINT `fk_attempt_task` FOREIGN KEY (`task_id`) REFERENCES `analysis_task` (`id`)
) ENGINE=InnoDB COMMENT='任务每次运行与重试记录表';

CREATE TABLE IF NOT EXISTS `task_dependency` (
  `child_task_id` BIGINT NOT NULL COMMENT '等待执行的下游任务',
  `prerequisite_task_id` BIGINT NOT NULL COMMENT '必须先成功的上游任务',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '依赖创建时间',
  PRIMARY KEY (`child_task_id`, `prerequisite_task_id`),
  KEY `idx_dependency_prerequisite` (`prerequisite_task_id`),
  CONSTRAINT `fk_dependency_child` FOREIGN KEY (`child_task_id`) REFERENCES `analysis_task` (`id`),
  CONSTRAINT `fk_dependency_prerequisite` FOREIGN KEY (`prerequisite_task_id`) REFERENCES `analysis_task` (`id`)
) ENGINE=InnoDB COMMENT='分析任务有向依赖关系；只有全部上游成功后才能执行';

CREATE TABLE IF NOT EXISTS `task_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务事件主键',
  `task_id` BIGINT NOT NULL COMMENT '分析任务主键',
  `attempt_no` INT NOT NULL DEFAULT 0 COMMENT '事件对应执行次数，排队事件为0',
  `event_seq` BIGINT NOT NULL COMMENT '任务内单调递增事件序号',
  `event_type` VARCHAR(60) NOT NULL COMMENT '事件类型',
  `payload_json` JSON DEFAULT NULL COMMENT '事件详细内容',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件发生时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_event_seq` (`task_id`, `event_seq`),
  KEY `idx_event_task_created` (`task_id`, `created_at`),
  CONSTRAINT `fk_event_task` FOREIGN KEY (`task_id`) REFERENCES `analysis_task` (`id`)
) ENGINE=InnoDB COMMENT='任务状态与进度事件表';

CREATE TABLE IF NOT EXISTS `analysis_result` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分析结果主键',
  `task_id` BIGINT NOT NULL COMMENT '来源任务主键',
  `result_type` VARCHAR(30) NOT NULL COMMENT '结果类型，与任务类型对应',
  `summary_json` JSON NOT NULL COMMENT '适合列表与图表直接展示的小型结果摘要',
  `artifact_object_key` VARCHAR(500) DEFAULT NULL COMMENT '完整结果文件在MinIO中的对象路径',
  `artifact_sha256` CHAR(64) DEFAULT NULL COMMENT '完整结果文件SHA-256校验值',
  `algorithm_version` VARCHAR(50) NOT NULL COMMENT '生成结果的算法版本',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_result_type` (`task_id`, `result_type`),
  KEY `idx_result_task_created` (`task_id`, `created_at` DESC),
  CONSTRAINT `fk_result_task` FOREIGN KEY (`task_id`) REFERENCES `analysis_task` (`id`)
) ENGINE=InnoDB COMMENT='分析结果摘要与文件索引表';

CREATE TABLE IF NOT EXISTS `outbox_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '待发送消息主键',
  `event_id` CHAR(36) NOT NULL COMMENT '消息全局唯一编号',
  `aggregate_type` VARCHAR(50) NOT NULL COMMENT '业务对象类型，例如ANALYSIS_TASK',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '业务对象编号',
  `event_type` VARCHAR(80) NOT NULL COMMENT '消息类型及版本',
  `topic_name` VARCHAR(100) NOT NULL COMMENT '目标Kafka主题',
  `message_key` VARCHAR(100) NOT NULL COMMENT 'Kafka消息键',
  `payload_json` JSON NOT NULL COMMENT '消息内容',
  `status` VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT '状态：NEW、SENDING、SENT、FAILED',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` DATETIME(3) DEFAULT NULL COMMENT '下次允许重试时间',
  `published_at` DATETIME(3) DEFAULT NULL COMMENT '成功发送时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_outbox_event_id` (`event_id`),
  KEY `idx_outbox_pending` (`status`, `next_retry_at`, `created_at`)
) ENGINE=InnoDB COMMENT='数据库与Kafka可靠发送的Outbox表';

CREATE TABLE IF NOT EXISTS `consumed_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '已消费消息主键',
  `event_id` CHAR(36) NOT NULL COMMENT 'Kafka业务消息唯一编号',
  `task_id` BIGINT NOT NULL COMMENT '消息对应分析任务主键',
  `status` VARCHAR(20) NOT NULL COMMENT '处理状态：PROCESSING、PROCESSED、FAILED',
  `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '处理失败原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '首次接收时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后处理时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consumed_event_id` (`event_id`),
  KEY `idx_consumed_task` (`task_id`),
  CONSTRAINT `fk_consumed_task` FOREIGN KEY (`task_id`) REFERENCES `analysis_task` (`id`)
) ENGINE=InnoDB COMMENT='Kafka消费幂等记录表';

CREATE TABLE IF NOT EXISTS `grid_transformer` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '变压器主键',
  `transformer_code` VARCHAR(40) NOT NULL COMMENT '变压器业务编号',
  `transformer_name` VARCHAR(100) NOT NULL COMMENT '变压器名称',
  `area_name` VARCHAR(100) NOT NULL COMMENT '所属区域',
  `rated_capacity_kw` DECIMAL(12,3) NOT NULL COMMENT '额定有功容量，单位kW',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '设备状态：ACTIVE、INACTIVE',
  `monitoring_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已加入实时监控清单',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grid_transformer_code` (`transformer_code`)
) ENGINE=InnoDB COMMENT='实时负荷预警使用的变压器档案';

-- 兼容已经创建过 grid_transformer 的本地数据库：只在字段缺失时执行一次迁移。
SET @grid_monitoring_column_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_transformer'
    AND COLUMN_NAME = 'monitoring_enabled'
);
SET @grid_monitoring_migration = IF(
  @grid_monitoring_column_exists = 0,
  'ALTER TABLE `grid_transformer` ADD COLUMN `monitoring_enabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否已加入实时监控清单'' AFTER `status`',
  'SELECT 1'
);
PREPARE grid_monitoring_statement FROM @grid_monitoring_migration;
EXECUTE grid_monitoring_statement;
DEALLOCATE PREPARE grid_monitoring_statement;
SET @grid_monitoring_initialization = IF(
  @grid_monitoring_column_exists = 0,
  'UPDATE `grid_transformer` SET `monitoring_enabled` = 1 WHERE `transformer_code` IN (''T001'', ''T002'', ''T003'')',
  'SELECT 1'
);
PREPARE grid_monitoring_init_statement FROM @grid_monitoring_initialization;
EXECUTE grid_monitoring_init_statement;
DEALLOCATE PREPARE grid_monitoring_init_statement;

-- 用户级监控关系。monitoring_enabled 仅作为“至少有一位用户监控”的系统级聚合开关。
SET @grid_user_monitor_table_exists = (
  SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_user_transformer_monitor'
);
CREATE TABLE IF NOT EXISTS `grid_user_transformer_monitor` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户监控关系主键',
  `user_id` BIGINT NOT NULL COMMENT '监控用户主键',
  `transformer_code` VARCHAR(40) NOT NULL COMMENT '被监控的变压器业务编号',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '加入监控时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grid_user_transformer` (`user_id`, `transformer_code`),
  KEY `idx_grid_monitor_transformer` (`transformer_code`, `user_id`),
  CONSTRAINT `fk_grid_monitor_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_grid_monitor_transformer` FOREIGN KEY (`transformer_code`) REFERENCES `grid_transformer` (`transformer_code`) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='每位用户当前选择监控的变压器';

-- 第一次升级时，把原来的全局监控清单复制给已有用户；后续启动绝不重新添加用户主动删除的监控。
SET @grid_user_monitor_migration = IF(
  @grid_user_monitor_table_exists = 0,
  'INSERT IGNORE INTO `grid_user_transformer_monitor` (`user_id`, `transformer_code`) SELECT u.id, t.transformer_code FROM `app_user` u JOIN `grid_transformer` t ON t.monitoring_enabled = 1 WHERE u.status = ''ACTIVE''',
  'SELECT 1'
);
PREPARE grid_user_monitor_statement FROM @grid_user_monitor_migration;
EXECUTE grid_user_monitor_statement;
DEALLOCATE PREPARE grid_user_monitor_statement;

CREATE TABLE IF NOT EXISTS `grid_transformer_metric` (
  `transformer_code` VARCHAR(40) NOT NULL COMMENT '变压器业务编号',
  `window_start` DATETIME(3) NOT NULL COMMENT 'Flink统计窗口开始时间',
  `window_end` DATETIME(3) NOT NULL COMMENT 'Flink统计窗口结束时间',
  `current_load_kw` DECIMAL(12,3) NOT NULL COMMENT '窗口内变压器汇总负荷',
  `rated_capacity_kw` DECIMAL(12,3) NOT NULL COMMENT '额定有功容量',
  `load_rate` DECIMAL(8,4) NOT NULL COMMENT '当前负载率，1表示100%',
  `historical_upper_kw` DECIMAL(12,3) DEFAULT NULL COMMENT 'Spark计算的当前时段P95上限',
  `status` VARCHAR(30) NOT NULL COMMENT 'NORMAL、WATCH、HISTORICAL_ANOMALY、OVERLOAD',
  `consecutive_abnormal_windows` INT NOT NULL DEFAULT 0 COMMENT '连续异常窗口数',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
  PRIMARY KEY (`transformer_code`),
  KEY `idx_grid_metric_status_updated` (`status`, `updated_at` DESC)
) ENGINE=InnoDB COMMENT='Flink输出的每台变压器最新实时指标';

CREATE TABLE IF NOT EXISTS `grid_transformer_metric_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '实时指标历史主键',
  `transformer_code` VARCHAR(40) NOT NULL COMMENT '变压器业务编号',
  `window_start` DATETIME(3) NOT NULL COMMENT 'Flink统计窗口开始时间',
  `window_end` DATETIME(3) NOT NULL COMMENT 'Flink统计窗口结束时间',
  `current_load_kw` DECIMAL(12,3) NOT NULL COMMENT '窗口内变压器汇总负荷',
  `rated_capacity_kw` DECIMAL(12,3) NOT NULL COMMENT '额定有功容量',
  `load_rate` DECIMAL(8,4) NOT NULL COMMENT '当前负载率，1表示100%',
  `historical_upper_kw` DECIMAL(12,3) DEFAULT NULL COMMENT 'Spark计算的当前时段P95上限',
  `status` VARCHAR(30) NOT NULL COMMENT 'NORMAL、WATCH、HISTORICAL_ANOMALY、OVERLOAD',
  `consecutive_abnormal_windows` INT NOT NULL DEFAULT 0 COMMENT '连续异常窗口数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '指标入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grid_metric_history_window` (`transformer_code`, `window_end`),
  KEY `idx_grid_metric_history_time` (`transformer_code`, `window_end` DESC)
) ENGINE=InnoDB COMMENT='Flink输出的变压器实时窗口指标历史';

CREATE TABLE IF NOT EXISTS `grid_risk_alert` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '预警主键',
  `user_id` BIGINT NOT NULL COMMENT '预警所属用户主键',
  `event_id` CHAR(36) NOT NULL COMMENT 'Flink生成的预警事件唯一编号',
  `transformer_code` VARCHAR(40) NOT NULL COMMENT '变压器业务编号',
  `alert_type` VARCHAR(40) NOT NULL COMMENT 'HISTORICAL_ANOMALY或OVERLOAD',
  `severity` VARCHAR(20) NOT NULL COMMENT 'MEDIUM或HIGH',
  `current_load_kw` DECIMAL(12,3) NOT NULL COMMENT '触发时汇总负荷',
  `rated_capacity_kw` DECIMAL(12,3) NOT NULL COMMENT '额定有功容量',
  `load_rate` DECIMAL(8,4) NOT NULL COMMENT '触发时负载率',
  `historical_upper_kw` DECIMAL(12,3) DEFAULT NULL COMMENT '触发时历史P95上限',
  `consecutive_windows` INT NOT NULL COMMENT '触发时连续异常窗口数',
  `event_time` DATETIME(3) NOT NULL COMMENT '预警业务时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_grid_alert_user_event` (`user_id`, `event_id`),
  KEY `idx_grid_alert_transformer_time` (`user_id`, `transformer_code`, `event_time` DESC),
  KEY `idx_grid_alert_type_time` (`alert_type`, `event_time` DESC),
  CONSTRAINT `fk_grid_alert_monitor` FOREIGN KEY (`user_id`, `transformer_code`)
    REFERENCES `grid_user_transformer_monitor` (`user_id`, `transformer_code`) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='变压器实时异常负荷预警记录';

-- 兼容旧库：旧预警没有用户归属，不能安全展示给任何用户，因此升级时直接清理。
SET @grid_alert_user_column_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_risk_alert'
    AND COLUMN_NAME = 'user_id'
);
SET @grid_alert_user_column_migration = IF(
  @grid_alert_user_column_exists = 0,
  'ALTER TABLE `grid_risk_alert` ADD COLUMN `user_id` BIGINT NULL COMMENT ''预警所属用户主键'' AFTER `id`',
  'SELECT 1'
);
PREPARE grid_alert_user_column_statement FROM @grid_alert_user_column_migration;
EXECUTE grid_alert_user_column_statement;
DEALLOCATE PREPARE grid_alert_user_column_statement;
DELETE FROM `grid_risk_alert` WHERE `user_id` IS NULL;
SET @grid_alert_user_nullable = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_risk_alert'
    AND COLUMN_NAME = 'user_id'
    AND IS_NULLABLE = 'YES'
);
SET @grid_alert_user_not_null_migration = IF(
  @grid_alert_user_nullable = 1,
  'ALTER TABLE `grid_risk_alert` MODIFY COLUMN `user_id` BIGINT NOT NULL COMMENT ''预警所属用户主键''',
  'SELECT 1'
);
PREPARE grid_alert_user_not_null_statement FROM @grid_alert_user_not_null_migration;
EXECUTE grid_alert_user_not_null_statement;
DEALLOCATE PREPARE grid_alert_user_not_null_statement;

SET @grid_alert_old_unique_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_risk_alert'
    AND INDEX_NAME = 'uk_grid_alert_event_id'
);
SET @grid_alert_drop_old_unique = IF(
  @grid_alert_old_unique_exists > 0,
  'ALTER TABLE `grid_risk_alert` DROP INDEX `uk_grid_alert_event_id`',
  'SELECT 1'
);
PREPARE grid_alert_drop_old_unique_statement FROM @grid_alert_drop_old_unique;
EXECUTE grid_alert_drop_old_unique_statement;
DEALLOCATE PREPARE grid_alert_drop_old_unique_statement;

SET @grid_alert_user_unique_exists = (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_risk_alert'
    AND INDEX_NAME = 'uk_grid_alert_user_event'
);
SET @grid_alert_add_user_unique = IF(
  @grid_alert_user_unique_exists = 0,
  'ALTER TABLE `grid_risk_alert` ADD UNIQUE KEY `uk_grid_alert_user_event` (`user_id`, `event_id`)',
  'SELECT 1'
);
PREPARE grid_alert_add_user_unique_statement FROM @grid_alert_add_user_unique;
EXECUTE grid_alert_add_user_unique_statement;
DEALLOCATE PREPARE grid_alert_add_user_unique_statement;

DELETE alert FROM `grid_risk_alert` alert
LEFT JOIN `grid_user_transformer_monitor` monitor
  ON monitor.user_id = alert.user_id AND monitor.transformer_code = alert.transformer_code
WHERE monitor.id IS NULL;

-- 复合外键保证即使“取消监控”和Kafka预警到达并发发生，数据库中也不会留下孤立预警。
SET @grid_alert_monitor_fk_exists = (
  SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
  WHERE CONSTRAINT_SCHEMA = 'loadflex_hub'
    AND TABLE_NAME = 'grid_risk_alert'
    AND CONSTRAINT_NAME = 'fk_grid_alert_monitor'
);
SET @grid_alert_add_monitor_fk = IF(
  @grid_alert_monitor_fk_exists = 0,
  'ALTER TABLE `grid_risk_alert` ADD CONSTRAINT `fk_grid_alert_monitor` FOREIGN KEY (`user_id`, `transformer_code`) REFERENCES `grid_user_transformer_monitor` (`user_id`, `transformer_code`) ON DELETE CASCADE',
  'SELECT 1'
);
PREPARE grid_alert_add_monitor_fk_statement FROM @grid_alert_add_monitor_fk;
EXECUTE grid_alert_add_monitor_fk_statement;
DEALLOCATE PREPARE grid_alert_add_monitor_fk_statement;

CREATE TABLE IF NOT EXISTS `audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计日志主键',
  `user_id` BIGINT DEFAULT NULL COMMENT '操作用户主键',
  `action` VARCHAR(80) NOT NULL COMMENT '操作类型',
  `resource_type` VARCHAR(50) NOT NULL COMMENT '被操作资源类型',
  `resource_id` VARCHAR(64) DEFAULT NULL COMMENT '被操作资源编号',
  `result` VARCHAR(20) NOT NULL COMMENT '操作结果：SUCCESS或FAILURE',
  `detail_json` JSON DEFAULT NULL COMMENT '不包含密码和令牌的操作详情',
  `ip_address` VARCHAR(64) DEFAULT NULL COMMENT '客户端IP地址',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_user_created` (`user_id`, `created_at` DESC)
) ENGINE=InnoDB COMMENT='用户与管理员关键操作审计表';

-- 检修转供仿真：使用简化馈线拓扑、版本化方案和逐时间片结果，所有操作仅用于规划，不控制真实设备。
SET @grid_transformer_feeder_exists = (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'grid_transformer' AND COLUMN_NAME = 'feeder_code'
);
SET @grid_transformer_feeder_sql = IF(
  @grid_transformer_feeder_exists = 0,
  'ALTER TABLE grid_transformer ADD COLUMN feeder_code VARCHAR(40) NULL COMMENT ''所属馈线编号'' AFTER area_name',
  'SELECT 1'
);
PREPARE grid_transformer_feeder_statement FROM @grid_transformer_feeder_sql;
EXECUTE grid_transformer_feeder_statement;
DEALLOCATE PREPARE grid_transformer_feeder_statement;

CREATE TABLE IF NOT EXISTS `grid_feeder` (
  `feeder_code` VARCHAR(40) NOT NULL COMMENT '馈线业务编号',
  `feeder_name` VARCHAR(100) NOT NULL COMMENT '馈线名称',
  `rated_capacity_kw` DECIMAL(12,3) NOT NULL COMMENT '规划仿真使用的额定有功容量',
  `source_name` VARCHAR(100) NOT NULL COMMENT '上级电源名称',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE或MAINTENANCE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`feeder_code`)
) ENGINE=InnoDB COMMENT='检修转供仿真使用的馈线档案';

CREATE TABLE IF NOT EXISTS `grid_switch` (
  `switch_code` VARCHAR(40) NOT NULL COMMENT '开关业务编号',
  `switch_name` VARCHAR(100) NOT NULL COMMENT '开关名称',
  `switch_type` VARCHAR(30) NOT NULL COMMENT 'BREAKER馈线开关或TIE联络开关',
  `from_feeder_code` VARCHAR(40) NOT NULL COMMENT '起始馈线',
  `to_feeder_code` VARCHAR(40) DEFAULT NULL COMMENT '目标馈线，普通馈线开关可为空',
  `normal_state` VARCHAR(10) NOT NULL COMMENT 'OPEN或CLOSED',
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`switch_code`),
  KEY `idx_grid_switch_from` (`from_feeder_code`),
  KEY `idx_grid_switch_to` (`to_feeder_code`)
) ENGINE=InnoDB COMMENT='馈线开关与常开联络开关档案';

CREATE TABLE IF NOT EXISTS `maintenance_plan` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `owner_id` BIGINT NOT NULL COMMENT '计划创建人',
  `plan_name` VARCHAR(150) NOT NULL,
  `reason` VARCHAR(500) NOT NULL,
  `outage_device_type` VARCHAR(30) NOT NULL COMMENT 'TRANSFORMER、FEEDER或SWITCH',
  `outage_device_code` VARCHAR(40) NOT NULL,
  `start_time` DATETIME(3) NOT NULL,
  `end_time` DATETIME(3) NOT NULL,
  `safety_limit` DECIMAL(5,4) NOT NULL DEFAULT 0.8000 COMMENT '0到1之间的容量安全线',
  `load_profile_type` VARCHAR(20) NOT NULL DEFAULT 'P90' COMMENT 'P50、P90或HISTORICAL_DAY',
  `historical_date` DATE DEFAULT NULL,
  `responsible_person` VARCHAR(100) DEFAULT NULL,
  `status` VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  `selected_scenario_id` BIGINT DEFAULT NULL,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_maintenance_owner_created` (`owner_id`, `created_at` DESC),
  KEY `idx_maintenance_device_time` (`outage_device_code`, `start_time`, `end_time`),
  CONSTRAINT `fk_maintenance_owner` FOREIGN KEY (`owner_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='检修计划主记录';

CREATE TABLE IF NOT EXISTS `transfer_scenario` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `scenario_name` VARCHAR(150) NOT NULL,
  `version_no` INT NOT NULL,
  `source_feeder_code` VARCHAR(40) NOT NULL,
  `target_feeder_code` VARCHAR(40) NOT NULL,
  `target_transformer_code` VARCHAR(40) NOT NULL,
  `tie_switch_code` VARCHAR(40) NOT NULL,
  `load_blocks_json` JSON NOT NULL,
  `status` VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  `latest_task_id` BIGINT DEFAULT NULL,
  `summary_json` JSON DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_plan_version` (`plan_id`, `version_no`),
  KEY `idx_transfer_plan` (`plan_id`, `created_at`),
  CONSTRAINT `fk_transfer_plan` FOREIGN KEY (`plan_id`) REFERENCES `maintenance_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_transfer_creator` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='检修计划的候选转供方案版本';

CREATE TABLE IF NOT EXISTS `transfer_operation_step` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scenario_id` BIGINT NOT NULL,
  `step_no` INT NOT NULL,
  `step_type` VARCHAR(30) NOT NULL COMMENT 'OPEN_SWITCH、CLOSE_SWITCH、TRANSFER_LOAD、RESTORE或SAFETY_CHECK',
  `device_code` VARCHAR(40) DEFAULT NULL,
  `instruction` VARCHAR(500) NOT NULL,
  `expected_state` VARCHAR(30) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_step_no` (`scenario_id`, `step_no`),
  CONSTRAINT `fk_transfer_step_scenario` FOREIGN KEY (`scenario_id`) REFERENCES `transfer_scenario` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='转供方案中可排序的现场操作步骤';

CREATE TABLE IF NOT EXISTS `transfer_simulation_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `scenario_id` BIGINT NOT NULL,
  `requested_by` BIGINT NOT NULL,
  `status` VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
  `progress` DECIMAL(5,2) NOT NULL DEFAULT 0,
  `stage` VARCHAR(100) DEFAULT NULL,
  `data_source` VARCHAR(30) DEFAULT NULL COMMENT 'CLICKHOUSE或LIVE_FALLBACK',
  `error_message` VARCHAR(1000) DEFAULT NULL,
  `started_at` DATETIME(3) DEFAULT NULL,
  `finished_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_simulation_scenario_created` (`scenario_id`, `created_at` DESC),
  CONSTRAINT `fk_simulation_scenario` FOREIGN KEY (`scenario_id`) REFERENCES `transfer_scenario` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_simulation_user` FOREIGN KEY (`requested_by`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='异步转供仿真任务及持久化进度';

CREATE TABLE IF NOT EXISTS `transfer_simulation_point` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `time_point` DATETIME(3) NOT NULL,
  `entity_type` VARCHAR(20) NOT NULL COMMENT 'TRANSFORMER或FEEDER',
  `entity_code` VARCHAR(40) NOT NULL,
  `before_load_kw` DECIMAL(12,3) NOT NULL,
  `transfer_load_kw` DECIMAL(12,3) NOT NULL,
  `after_load_kw` DECIMAL(12,3) NOT NULL,
  `capacity_kw` DECIMAL(12,3) NOT NULL,
  `load_rate` DECIMAL(8,4) NOT NULL,
  `risk_status` VARCHAR(20) NOT NULL COMMENT 'NORMAL、WARNING或OVERLOAD',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_simulation_point` (`task_id`, `time_point`, `entity_type`, `entity_code`),
  KEY `idx_simulation_point_time` (`task_id`, `time_point`),
  CONSTRAINT `fk_simulation_point_task` FOREIGN KEY (`task_id`) REFERENCES `transfer_simulation_task` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='仿真逐时刻逐设备结果，用于拓扑动画与曲线';

CREATE TABLE IF NOT EXISTS `plan_review_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `action` VARCHAR(30) NOT NULL COMMENT 'SUBMIT、APPROVE或REJECT',
  `operator_id` BIGINT NOT NULL,
  `comment_text` VARCHAR(1000) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_review_plan_created` (`plan_id`, `created_at`),
  CONSTRAINT `fk_review_plan` FOREIGN KEY (`plan_id`) REFERENCES `maintenance_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_review_operator` FOREIGN KEY (`operator_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='检修计划提交与审核轨迹';

CREATE TABLE IF NOT EXISTS `planning_agent_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `title` VARCHAR(150) NOT NULL,
  `linked_plan_id` BIGINT DEFAULT NULL,
  `draft_json` JSON DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_agent_session_user` (`user_id`, `updated_at` DESC),
  CONSTRAINT `fk_agent_session_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='检修方案Agent用户会话';

CREATE TABLE IF NOT EXISTS `planning_agent_message` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT NOT NULL,
  `role_code` VARCHAR(20) NOT NULL COMMENT 'USER、ASSISTANT或SYSTEM',
  `content_text` MEDIUMTEXT NOT NULL,
  `metadata_json` JSON DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_agent_message_session` (`session_id`, `id`),
  CONSTRAINT `fk_agent_message_session` FOREIGN KEY (`session_id`) REFERENCES `planning_agent_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Agent多轮消息';

CREATE TABLE IF NOT EXISTS `planning_agent_run` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `status` VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
  `current_stage` VARCHAR(100) DEFAULT NULL,
  `error_message` VARCHAR(1000) DEFAULT NULL,
  `input_tokens` INT NOT NULL DEFAULT 0,
  `output_tokens` INT NOT NULL DEFAULT 0,
  `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at` DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_agent_run_session` (`session_id`, `id`),
  CONSTRAINT `fk_agent_run_session` FOREIGN KEY (`session_id`) REFERENCES `planning_agent_session` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_run_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='一次可取消、可恢复的Agent运行';

CREATE TABLE IF NOT EXISTS `planning_agent_step` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `step_no` INT NOT NULL,
  `step_type` VARCHAR(30) NOT NULL COMMENT 'PLAN、TOOL、RESULT或ERROR',
  `tool_name` VARCHAR(80) DEFAULT NULL,
  `status` VARCHAR(30) NOT NULL,
  `summary_text` VARCHAR(1000) NOT NULL,
  `detail_json` JSON DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_step_no` (`run_id`, `step_no`),
  CONSTRAINT `fk_agent_step_run` FOREIGN KEY (`run_id`) REFERENCES `planning_agent_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Agent计划与工具执行轨迹';

CREATE TABLE IF NOT EXISTS `planning_agent_approval` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `session_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `action_type` VARCHAR(50) NOT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `idempotency_key` VARCHAR(100) NOT NULL,
  `preview_json` JSON NOT NULL,
  `payload_json` JSON NOT NULL,
  `decided_at` DATETIME(3) DEFAULT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_approval_idempotency` (`idempotency_key`),
  KEY `idx_agent_approval_session` (`session_id`, `status`),
  CONSTRAINT `fk_agent_approval_run` FOREIGN KEY (`run_id`) REFERENCES `planning_agent_run` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_approval_session` FOREIGN KEY (`session_id`) REFERENCES `planning_agent_session` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_agent_approval_user` FOREIGN KEY (`user_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='Agent写操作人工审批及幂等记录';

-- 交互式仿真调控台：用户逐步操作开关和负荷转移，记录每一步及拓扑状态。
CREATE TABLE IF NOT EXISTS `grid_control_session` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT NOT NULL,
  `scenario_id` BIGINT NOT NULL,
  `created_by` BIGINT NOT NULL,
  `session_name` VARCHAR(150) NOT NULL,
  `status` VARCHAR(30) NOT NULL DEFAULT 'READY' COMMENT 'READY、RUNNING、COMPLETED、ROLLED_BACK或FAILED',
  `current_step_no` INT NOT NULL DEFAULT 0,
  `source_switch_state` VARCHAR(10) NOT NULL DEFAULT 'CLOSED',
  `tie_switch_state` VARCHAR(10) NOT NULL DEFAULT 'OPEN',
  `supply_state` VARCHAR(30) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL、INTERRUPTED或BACKUP',
  `transferred` TINYINT(1) NOT NULL DEFAULT 0,
  `verified` TINYINT(1) NOT NULL DEFAULT 0,
  `version` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `completed_at` DATETIME(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_control_plan_created` (`plan_id`, `created_at` DESC),
  KEY `idx_control_scenario_created` (`scenario_id`, `created_at` DESC),
  CONSTRAINT `fk_control_plan` FOREIGN KEY (`plan_id`) REFERENCES `maintenance_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_control_scenario` FOREIGN KEY (`scenario_id`) REFERENCES `transfer_scenario` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_control_creator` FOREIGN KEY (`created_by`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='备用供电交互式仿真调控会话';

CREATE TABLE IF NOT EXISTS `grid_control_action` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `control_session_id` BIGINT NOT NULL,
  `sequence_no` INT NOT NULL,
  `action_type` VARCHAR(40) NOT NULL COMMENT 'CONFIRM、OPEN_SOURCE、CLOSE_TIE、TRANSFER、VERIFY或ROLLBACK',
  `device_code` VARCHAR(40) DEFAULT NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED',
  `instruction` VARCHAR(500) NOT NULL,
  `before_state_json` JSON NOT NULL,
  `after_state_json` JSON NOT NULL,
  `result_json` JSON DEFAULT NULL,
  `operator_id` BIGINT NOT NULL,
  `idempotency_key` VARCHAR(100) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_control_action_sequence` (`control_session_id`, `sequence_no`),
  UNIQUE KEY `uk_control_action_idempotency` (`idempotency_key`),
  CONSTRAINT `fk_control_action_session` FOREIGN KEY (`control_session_id`) REFERENCES `grid_control_session` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_control_action_operator` FOREIGN KEY (`operator_id`) REFERENCES `app_user` (`id`)
) ENGINE=InnoDB COMMENT='仿真调控操作记录和前后状态快照';
