# 多用电单元测试数据

以下文件都包含多个用电单元及连续历史负荷，适用于按用电单元聚类、集群预测、基线和调节潜力测试。

| 文件 | 格式 | 用电单元 | 时间范围/间隔 | 字段映射 |
|---|---|---:|---|---|
| `multi-unit-load-15min-long.csv` | 标准长表 | 24 | 45天/15分钟 | `userColumn=user_id`、`timeColumn=timestamp`、`valueColumn=load_kw`、`unit=kW`、`intervalMinutes=15` |
| `multi-unit-load-30min-custom-columns.csv` | 自定义字段长表 | 18 | 42天/30分钟 | `userColumn=用电单元编码`、`timeColumn=采集时刻`、`valueColumn=有功功率_MW`、`unit=MW`、`intervalMinutes=30` |
| `multi-unit-load-15min-wide.csv` | 宽表 | 16 | 35天/15分钟 | `userColumn=user_id`、`dateColumn=data_date`、`unit=kW`、`intervalMinutes=15`；时间字段和负荷字段留空 |

数据包含居民、办公、商业和工业四类人为构造的典型曲线，并叠加星期效应、缓慢趋势和小幅随机波动。所有随机数都使用固定规则生成，重复执行 `generate_demo_data.py` 会得到一致的数据。
