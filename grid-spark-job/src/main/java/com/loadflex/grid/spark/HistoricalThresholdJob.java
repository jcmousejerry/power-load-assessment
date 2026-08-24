package com.loadflex.grid.spark;

import static org.apache.spark.sql.functions.avg;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.count;
import static org.apache.spark.sql.functions.dayofweek;
import static org.apache.spark.sql.functions.expr;
import static org.apache.spark.sql.functions.hour;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.max;
import static org.apache.spark.sql.functions.minute;
import static org.apache.spark.sql.functions.round;
import static org.apache.spark.sql.functions.sum;
import static org.apache.spark.sql.functions.to_timestamp;
import static org.apache.spark.sql.functions.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;

public final class HistoricalThresholdJob {
    private HistoricalThresholdJob() {}

    public static void main(String[] args) {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "用法：HistoricalThresholdJob <ClickHouse JDBC URL> <Kafka地址> <阈值Topic> [模型版本]");
        }
        String jdbcUrl = args[0];
        String kafkaBootstrapServers = args[1];
        String thresholdTopic = args[2];
        String modelVersion = args.length >= 4 ? args[3] : "spark-p95-v1";
        String username = environment("CLICKHOUSE_USERNAME", "loadflex");
        String password = environment("CLICKHOUSE_PASSWORD", "123456");

        SparkSession spark = SparkSession.builder()
                .appName("LoadFlex historical risk threshold")
                .getOrCreate();
        spark.sparkContext().setLogLevel("WARN");
        try {
            Dataset<Row> raw = spark.read()
                    .format("jdbc")
                    .option("url", jdbcUrl)
                    .option("driver", "ru.yandex.clickhouse.ClickHouseDriver")
                    .option("user", username)
                    .option("password", password)
                    .option("dbtable", telemetrySource())
                    .option("fetchsize", "10000")
                    .load()
                    .withColumn("eventTimestamp", to_timestamp(col("event_time")))
                    .filter(col("eventTimestamp").isNotNull().and(col("load_kw").geq(0)));

            Dataset<Row> transformerSamples = raw.groupBy(col("transformer_id"), col("eventTimestamp"))
                    .agg(sum("load_kw").alias("totalLoadKw"))
                    .withColumn(
                            "dayType",
                            when(dayofweek(col("eventTimestamp")).isin(1, 7), "WEEKEND")
                                    .otherwise("WORKDAY"))
                    .withColumn("hourOfDay", hour(col("eventTimestamp")));

            Dataset<Row> quarterHourProfiles = transformerSamples
                    .withColumn(
                            "slotOfDay",
                            hour(col("eventTimestamp")).multiply(4)
                                    .plus(minute(col("eventTimestamp")).divide(15).cast("int")))
                    .groupBy(col("transformer_id"), col("dayType"), col("slotOfDay"))
                    .agg(
                            expr("percentile_approx(totalLoadKw, 0.50, 10000)").alias("p50LoadKw"),
                            expr("percentile_approx(totalLoadKw, 0.90, 10000)").alias("p90LoadKw"),
                            expr("percentile_approx(totalLoadKw, 0.95, 10000)").alias("p95LoadKw"),
                            count(lit(1)).alias("sampleCount"))
                    .select(
                            col("transformer_id"),
                            col("dayType").alias("day_type"),
                            col("slotOfDay").alias("slot_of_day"),
                            round(col("p50LoadKw"), 3).alias("p50_load_kw"),
                            round(col("p90LoadKw"), 3).alias("p90_load_kw"),
                            round(col("p95LoadKw"), 3).alias("p95_load_kw"),
                            col("sampleCount").alias("sample_count"),
                            lit(modelVersion).alias("model_version"))
                    .orderBy(col("transformer_id"), col("day_type"), col("slot_of_day"))
                    .cache();

            Dataset<Row> thresholds = transformerSamples
                    .groupBy(col("transformer_id"), col("dayType"), col("hourOfDay"))
                    .agg(
                            avg("totalLoadKw").alias("averageLoadKw"),
                            expr("percentile_approx(totalLoadKw, 0.95, 10000)").alias("p95LoadKw"),
                            max("totalLoadKw").alias("maximumLoadKw"),
                            count(lit(1)).alias("sampleCount"))
                    .select(
                            col("transformer_id"),
                            col("dayType").alias("day_type"),
                            col("hourOfDay").alias("hour_of_day"),
                            round(col("averageLoadKw"), 3).alias("average_load_kw"),
                            round(col("p95LoadKw"), 3).alias("p95_load_kw"),
                            round(col("maximumLoadKw"), 3).alias("maximum_load_kw"),
                            col("sampleCount").alias("sample_count"),
                            lit(modelVersion).alias("model_version"))
                    .orderBy(col("transformer_id"), col("day_type"), col("hour_of_day"))
                    .cache();

            long ruleCount = thresholds.count();
            thresholds.coalesce(1).write()
                    .format("jdbc")
                    .option("url", jdbcUrl)
                    .option("driver", "ru.yandex.clickhouse.ClickHouseDriver")
                    .option("user", username)
                    .option("password", password)
                    .option("dbtable", "loadflex_grid.transformer_risk_threshold")
                    .option("batchsize", "1000")
                    .mode(SaveMode.Append)
                    .save();
            long publishedCount = publishThresholds(
                    thresholds, kafkaBootstrapServers, thresholdTopic, modelVersion);
            long profileCount = quarterHourProfiles.count();
            quarterHourProfiles.coalesce(1).write()
                    .format("jdbc")
                    .option("url", jdbcUrl)
                    .option("driver", "ru.yandex.clickhouse.ClickHouseDriver")
                    .option("user", username)
                    .option("password", password)
                    .option("dbtable", "loadflex_grid.transformer_load_profile_15m")
                    .option("batchsize", "1000")
                    .mode(SaveMode.Append)
                    .save();
            System.out.println("Spark动态阈值计算完成，已写入ClickHouse并发布Kafka，规则数量=" + ruleCount
                    + "，发布数量=" + publishedCount + "，15分钟负荷画像数量=" + profileCount
                    + "，模型版本=" + modelVersion);
        } finally {
            spark.stop();
        }
    }

    private static String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private static String telemetrySource() {
        return "(SELECT event_time, transformer_id, meter_id, load_kw "
                + "FROM loadflex_grid.transformer_history "
                + "UNION ALL "
                + "SELECT toDateTime(event_hour) AS event_time, transformer_id, meter_id, load_kw "
                + "FROM (SELECT toStartOfHour(event_time) AS event_hour, transformer_id, meter_id, "
                + "avg(load_kw) AS load_kw FROM loadflex_grid.transformer_realtime_telemetry FINAL "
                + "WHERE event_time >= now() - INTERVAL 7 DAY "
                + "GROUP BY event_hour, transformer_id, meter_id)) AS telemetry_source";
    }

    private static long publishThresholds(
            Dataset<Row> thresholds, String bootstrapServers, String topic, String modelVersion) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        ObjectMapper mapper = new ObjectMapper();
        long published = 0;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            Iterator<Row> iterator = thresholds.toLocalIterator();
            while (iterator.hasNext()) {
                Row row = iterator.next();
                Map<String, Object> message = new LinkedHashMap<>();
                message.put("transformerId", row.getAs("transformer_id"));
                message.put("dayType", row.getAs("day_type"));
                message.put("hourOfDay", row.getAs("hour_of_day"));
                message.put("averageLoadKw", row.getAs("average_load_kw"));
                message.put("p95LoadKw", row.getAs("p95_load_kw"));
                message.put("maximumLoadKw", row.getAs("maximum_load_kw"));
                message.put("sampleCount", row.getAs("sample_count"));
                message.put("modelVersion", modelVersion);
                String key = message.get("transformerId") + "|" + message.get("dayType") + "|"
                        + message.get("hourOfDay");
                try {
                    Future<RecordMetadata> result =
                            producer.send(new ProducerRecord<>(topic, key, mapper.writeValueAsString(message)));
                    result.get();
                    published++;
                } catch (Exception exception) {
                    throw new IllegalStateException("Spark阈值发布Kafka失败，key=" + key, exception);
                }
            }
            producer.flush();
        }
        return published;
    }

}
