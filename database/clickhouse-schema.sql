CREATE DATABASE IF NOT EXISTS loadflex_grid;

CREATE TABLE IF NOT EXISTS loadflex_grid.transformer_history
(
    event_time DateTime('Asia/Shanghai'),
    transformer_id String,
    meter_id String,
    load_kw Float64,
    ingested_at DateTime('Asia/Shanghai') DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(event_time)
ORDER BY (transformer_id, event_time, meter_id);

CREATE TABLE IF NOT EXISTS loadflex_grid.transformer_risk_threshold
(
    transformer_id String,
    day_type LowCardinality(String),
    hour_of_day UInt8,
    average_load_kw Float64,
    p95_load_kw Float64,
    maximum_load_kw Float64,
    sample_count UInt64,
    model_version String,
    calculated_at DateTime('Asia/Shanghai') DEFAULT now()
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(calculated_at)
ORDER BY (model_version, transformer_id, day_type, hour_of_day)
TTL calculated_at + INTERVAL 7 DAY DELETE;

CREATE TABLE IF NOT EXISTS loadflex_grid.transformer_realtime_telemetry
(
    event_time DateTime64(3, 'Asia/Shanghai'),
    event_id String,
    transformer_id String,
    meter_id String,
    load_kw Float64,
    rated_capacity_kw Float64,
    ingested_at DateTime64(3, 'Asia/Shanghai') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (transformer_id, event_time, meter_id, event_id)
TTL event_time + INTERVAL 7 DAY DELETE;

CREATE VIEW IF NOT EXISTS loadflex_grid.transformer_realtime_load AS
SELECT
    event_time,
    transformer_id,
    sum(load_kw) AS total_load_kw,
    max(rated_capacity_kw) AS rated_capacity_kw
FROM loadflex_grid.transformer_realtime_telemetry FINAL
GROUP BY event_time, transformer_id;

ALTER TABLE loadflex_grid.transformer_risk_threshold
    MODIFY TTL calculated_at + INTERVAL 7 DAY DELETE;

ALTER TABLE loadflex_grid.transformer_realtime_telemetry
    MODIFY TTL event_time + INTERVAL 7 DAY DELETE;

CREATE TABLE IF NOT EXISTS loadflex_grid.transformer_load_profile_15m
(
    transformer_id String,
    day_type LowCardinality(String),
    slot_of_day UInt16,
    p50_load_kw Float64,
    p90_load_kw Float64,
    p95_load_kw Float64,
    sample_count UInt64,
    model_version String,
    calculated_at DateTime('Asia/Shanghai') DEFAULT now()
)
ENGINE = ReplacingMergeTree(calculated_at)
PARTITION BY toYYYYMM(calculated_at)
ORDER BY (transformer_id, day_type, slot_of_day, model_version)
TTL calculated_at + INTERVAL 30 DAY DELETE;
