CREATE TABLE `user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) NOT NULL UNIQUE COMMENT '用户名（全表唯一）',
  `password` varchar(100) NOT NULL COMMENT '密码（存储原始密码经过加密后得到的内容）',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像（头像图片文件存储在minio中的路径）',
  `user_type` tinyint(4) NOT NULL DEFAULT '0' COMMENT '用户类型（0:普通用户, 1:管理员）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '用户信息数据创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '用户信息数据更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户信息表';

CREATE TABLE `dataset_info` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '数据集信息表的主键',
  `name` varchar(255) NOT NULL COMMENT '数据集名称',
  `path` varchar(255) NOT NULL COMMENT '数据集存储路径（数据集文件在minio中的存储路径）',
  `user_id` bigint(20) NOT NULL COMMENT '数据集关联的用户id',
  `start_date` date NOT NULL COMMENT '数据集中数据的开始日期',
  `end_date` date NOT NULL COMMENT '数据集中数据的结束日期',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'mysql表中数据创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'mysql表中数据更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据集信息表';

CREATE TABLE `data_analysis_task` (
    `id` BIGINT NOT NULL COMMENT '任务信息主键',
    `user_id` BIGINT NOT NULL COMMENT '创建任务的用户id',
    `dataset_id` BIGINT NOT NULL COMMENT '任务关联的数据集id',
    `task_type` TINYINT NOT NULL COMMENT '任务类型（0:基本统计特征提取, 1:用户聚类, 2:负荷预测）',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态（0:未完成, 1:已完成）',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务信息数据更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_dataset_id` (`dataset_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据分析任务信息表';

CREATE TABLE `statistical_feature_result` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint(20) NOT NULL COMMENT '对应的任务id',
  `user_count` int(11) NOT NULL COMMENT '数据集用户数量',
  `sampling_frequency` double NOT NULL COMMENT '采样频率',
  `peak_load` double NOT NULL COMMENT '峰值负荷',
  `valley_load` double NOT NULL COMMENT '谷值负荷',
  `average_load` double NOT NULL COMMENT '平均负荷',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务执行结果信息数据创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务执行结果信息数据更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基本统计特征提取任务执行结果表';

CREATE TABLE `user_clustering_result` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint(20) NOT NULL COMMENT '对应的任务id',
  `cluster_id` int(11) NOT NULL COMMENT '聚簇id',
  `cluster_user_info` text NOT NULL COMMENT '聚簇用户信息（字符串）',
  `cluster_load_pattern` text NOT NULL COMMENT '聚簇负荷模式数据（字符串）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务执行结果信息数据创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务执行结果信息数据更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户聚类任务执行结果表';

CREATE TABLE `load_forecast_result` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint(20) NOT NULL COMMENT '对应的任务id',
  `data_type` tinyint(4) NOT NULL COMMENT '负荷数据类型（0-历史负荷, 1-预测负荷）',
  `forecast_time` datetime NOT NULL COMMENT '时间（历史时间或预测时间）',
  `load_value` double NOT NULL COMMENT '负荷值（数值）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任务执行结果信息数据创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '任务执行结果信息数据更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`),
  KEY `idx_forecast_time` (`forecast_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='负荷预测任务执行结果表';

CREATE TABLE `load_forecast_model` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `task_id` bigint(20) NOT NULL COMMENT '模型对应的任务id',
  `model_type` tinyint(4) NOT NULL COMMENT '模型类型（0-单因素预测模型，1-多因素预测模型）',
  `model_path` varchar(255) NOT NULL COMMENT '模型存储路径（模型文件在MinIO中的存储路径）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '模型信息数据创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '模型信息数据更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='负荷预测模型信息表';

