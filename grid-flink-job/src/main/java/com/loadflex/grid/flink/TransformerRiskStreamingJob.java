package com.loadflex.grid.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.flink.streaming.util.serialization.SimpleStringSchema;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

public final class TransformerRiskStreamingJob {
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final MapStateDescriptor<String, ThresholdModel> THRESHOLD_STATE = new MapStateDescriptor<>(
            "spark-thresholds",
            TypeInformation.of(String.class),
            TypeInformation.of(new TypeHint<ThresholdModel>() {}));
    private static final OutputTag<RiskAlert> ALERT_OUTPUT =
            new OutputTag<RiskAlert>("risk-alerts", TypeInformation.of(RiskAlert.class));

    private TransformerRiskStreamingJob() {}

    public static void main(String[] args) throws Exception {
        ParameterTool parameters = ParameterTool.fromArgs(args);
        String bootstrapServers = parameters.get("bootstrap", "127.0.0.1:9092");
        int windowSeconds = parameters.getInt("window-seconds", 10);
        int requiredWindows = parameters.getInt("required-windows", 3);
        int repeatAlertWindows = Math.max(1, parameters.getInt("repeat-alert-windows", 6));

        Properties kafkaProperties = new Properties();
        kafkaProperties.setProperty("bootstrap.servers", bootstrapServers);

        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();
        environment.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        environment.setParallelism(1);
        environment.enableCheckpointing(10000L, CheckpointingMode.EXACTLY_ONCE);
        environment.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.seconds(5)));

        FlinkKafkaConsumer<String> telemetryConsumer = new FlinkKafkaConsumer<>(
                "grid-telemetry-raw",
                new SimpleStringSchema(),
                consumerProperties(bootstrapServers, "grid-flink-telemetry"));
        telemetryConsumer.setStartFromLatest();
        DataStream<TelemetryEvent> telemetry = environment
                .addSource(telemetryConsumer)
                .name("Kafka实时遥测源")
                .flatMap(new TelemetryParser())
                .name("遥测JSON校验")
                .assignTimestampsAndWatermarks(
                        new BoundedOutOfOrdernessTimestampExtractor<TelemetryEvent>(
                                org.apache.flink.streaming.api.windowing.time.Time.seconds(5)) {
                            @Override
                            public long extractTimestamp(TelemetryEvent event) {
                                return event.eventTimeEpochMs;
                            }
                        });

        FlinkKafkaConsumer<String> thresholdConsumer = new FlinkKafkaConsumer<>(
                "grid-risk-threshold",
                new SimpleStringSchema(),
                consumerProperties(bootstrapServers, "grid-flink-threshold"));
        thresholdConsumer.setStartFromEarliest();
        BroadcastStream<ThresholdModel> thresholds = environment
                .addSource(thresholdConsumer)
                .name("Spark动态阈值源")
                .flatMap(new ThresholdParser())
                .name("阈值JSON校验")
                .broadcast(THRESHOLD_STATE);

        DataStream<TransformerWindow> windows = telemetry
                .keyBy((KeySelector<TelemetryEvent, String>) event -> event.transformerId)
                .timeWindow(org.apache.flink.streaming.api.windowing.time.Time.seconds(windowSeconds))
                .process(new TransformerWindowFunction())
                .name("变压器窗口负荷汇总");

        SingleOutputStreamOperator<TransformerMetric> metrics = windows.keyBy(
                        (KeySelector<TransformerWindow, String>) window -> window.transformerCode)
                .connect(thresholds)
                .process(new RiskEvaluator(requiredWindows, repeatAlertWindows))
                .name("动态阈值与连续异常判断");

        SinkFunction<String> metricSink =
                new FlinkKafkaProducer<>("grid-transformer-metric", new SimpleStringSchema(), kafkaProperties);
        metrics.map(new JsonWriter<TransformerMetric>())
                .name("指标JSON序列化")
                .addSink(metricSink)
                .name("实时指标Kafka输出");

        SinkFunction<String> alertSink =
                new FlinkKafkaProducer<>("grid-risk-alert", new SimpleStringSchema(), kafkaProperties);
        metrics.getSideOutput(ALERT_OUTPUT)
                .map(new JsonWriter<RiskAlert>())
                .name("预警JSON序列化")
                .addSink(alertSink)
                .name("风险预警Kafka输出");

        environment.execute("LoadFlex transformer real-time risk monitoring");
    }

    private static Properties consumerProperties(String bootstrapServers, String groupId) {
        Properties properties = new Properties();
        properties.setProperty("bootstrap.servers", bootstrapServers);
        properties.setProperty("group.id", groupId);
        properties.setProperty("auto.offset.reset", "earliest");
        return properties;
    }

    private static final class TelemetryParser extends RichFlatMapFunction<String, TelemetryEvent> {
        private transient ObjectMapper mapper;

        @Override
        public void open(Configuration parameters) {
            mapper = new ObjectMapper();
        }

        @Override
        public void flatMap(String value, Collector<TelemetryEvent> output) throws Exception {
            JsonNode node = mapper.readTree(normalizeJson(value));
            if (node.isTextual()) {
                node = mapper.readTree(node.asText());
            }
            String transformerId = node.path("transformerId").asText();
            String meterId = node.path("meterId").asText();
            long eventTime = node.path("eventTimeEpochMs").asLong();
            double loadKw = node.path("loadKw").asDouble(-1.0);
            double ratedCapacityKw = node.path("ratedCapacityKw").asDouble(-1.0);
            if (!transformerId.isEmpty() && !meterId.isEmpty() && eventTime > 0 && loadKw >= 0 && ratedCapacityKw > 0) {
                output.collect(new TelemetryEvent(transformerId, meterId, eventTime, loadKw, ratedCapacityKw));
            }
        }
    }

    private static final class ThresholdParser extends RichFlatMapFunction<String, ThresholdModel> {
        private transient ObjectMapper mapper;

        @Override
        public void open(Configuration parameters) {
            mapper = new ObjectMapper();
        }

        @Override
        public void flatMap(String value, Collector<ThresholdModel> output) throws Exception {
            JsonNode node = mapper.readTree(normalizeJson(value));
            if (node.isTextual()) {
                node = mapper.readTree(node.asText());
            }
            String transformerId = node.path("transformerId").asText();
            String dayType = node.path("dayType").asText();
            int hourOfDay = node.path("hourOfDay").asInt(-1);
            double p95LoadKw = node.path("p95LoadKw").asDouble(-1.0);
            if (!transformerId.isEmpty() && !dayType.isEmpty() && hourOfDay >= 0 && p95LoadKw > 0) {
                output.collect(new ThresholdModel(
                        transformerId,
                        dayType,
                        hourOfDay,
                        node.path("averageLoadKw").asDouble(),
                        p95LoadKw,
                        node.path("modelVersion").asText()));
            }
        }
    }

    private static final class TransformerWindowFunction
            extends ProcessWindowFunction<TelemetryEvent, TransformerWindow, String, TimeWindow> {
        @Override
        public void process(
                String transformerCode,
                Context context,
                Iterable<TelemetryEvent> events,
                Collector<TransformerWindow> output) {
            Map<Long, Double> totalsBySamplingTime = new HashMap<>();
            double ratedCapacityKw = 0;
            for (TelemetryEvent event : events) {
                totalsBySamplingTime.merge(event.eventTimeEpochMs, event.loadKw, Double::sum);
                ratedCapacityKw = Math.max(ratedCapacityKw, event.ratedCapacityKw);
            }
            if (totalsBySamplingTime.isEmpty()) {
                return;
            }
            double total = 0;
            for (double samplingTotal : totalsBySamplingTime.values()) {
                total += samplingTotal;
            }
            double averageLoadKw = total / totalsBySamplingTime.size();
            output.collect(new TransformerWindow(
                    transformerCode,
                    context.window().getStart(),
                    context.window().getEnd(),
                    round(averageLoadKw),
                    round(ratedCapacityKw)));
        }
    }

    private static final class RiskEvaluator
            extends KeyedBroadcastProcessFunction<String, TransformerWindow, ThresholdModel, TransformerMetric> {
        private final int requiredWindows;
        private final int repeatAlertWindows;
        private transient ValueState<String> lastCondition;
        private transient ValueState<Integer> consecutiveWindows;
        private transient ValueState<String> activeAlertType;
        private transient ValueState<Integer> lastAlertConsecutiveWindow;

        private RiskEvaluator(int requiredWindows, int repeatAlertWindows) {
            this.requiredWindows = requiredWindows;
            this.repeatAlertWindows = repeatAlertWindows;
        }

        @Override
        public void open(Configuration parameters) {
            lastCondition = getRuntimeContext().getState(new ValueStateDescriptor<>("last-condition", String.class));
            consecutiveWindows =
                    getRuntimeContext().getState(new ValueStateDescriptor<>("consecutive-windows", Integer.class, 0));
            activeAlertType =
                    getRuntimeContext().getState(new ValueStateDescriptor<>("active-alert-type", String.class));
            lastAlertConsecutiveWindow = getRuntimeContext()
                    .getState(new ValueStateDescriptor<>("last-alert-consecutive-window", Integer.class));
        }

        @Override
        public void processBroadcastElement(ThresholdModel model, Context context, Collector<TransformerMetric> output)
                throws Exception {
            BroadcastState<String, ThresholdModel> state = context.getBroadcastState(THRESHOLD_STATE);
            state.put(model.key(), model);
        }

        @Override
        public void processElement(
                TransformerWindow window, ReadOnlyContext context, Collector<TransformerMetric> output)
                throws Exception {
            LocalDateTime localTime =
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(window.windowEndEpochMs), PROJECT_ZONE);
            String dayType = isWeekend(localTime.getDayOfWeek()) ? "WEEKEND" : "WORKDAY";
            String thresholdKey = ThresholdModel.key(window.transformerCode, dayType, localTime.getHour());
            ReadOnlyBroadcastState<String, ThresholdModel> state = context.getBroadcastState(THRESHOLD_STATE);
            ThresholdModel threshold = state.get(thresholdKey);
            Double historicalUpper = threshold == null ? null : threshold.p95LoadKw;
            double loadRate = window.currentLoadKw / window.ratedCapacityKw;

            String condition = null;
            if (loadRate > 1.0) {
                condition = "OVERLOAD";
            } else if (historicalUpper != null && window.currentLoadKw > historicalUpper) {
                condition = "HISTORICAL_ANOMALY";
            }

            int consecutive = updateConsecutive(condition);
            String status = condition != null ? condition : (loadRate >= 0.8 ? "WATCH" : "NORMAL");
            TransformerMetric metric = new TransformerMetric(
                    window.transformerCode,
                    window.windowStartEpochMs,
                    window.windowEndEpochMs,
                    round(window.currentLoadKw),
                    round(window.ratedCapacityKw),
                    round(loadRate),
                    historicalUpper == null ? null : round(historicalUpper),
                    status,
                    consecutive,
                    threshold == null ? null : threshold.modelVersion);
            output.collect(metric);

            String active = activeAlertType.value();
            if (condition == null) {
                activeAlertType.clear();
                lastAlertConsecutiveWindow.clear();
            } else if (consecutive >= requiredWindows) {
                Integer lastAlertWindow = lastAlertConsecutiveWindow.value();
                boolean firstAlertForCondition = !condition.equals(active) || lastAlertWindow == null;
                boolean repeatAlertDue = condition.equals(active)
                        && lastAlertWindow != null
                        && consecutive - lastAlertWindow >= repeatAlertWindows;
                if (firstAlertForCondition || repeatAlertDue) {
                    RiskAlert alert = RiskAlert.from(metric, condition, consecutive);
                    context.output(ALERT_OUTPUT, alert);
                    activeAlertType.update(condition);
                    lastAlertConsecutiveWindow.update(consecutive);
                }
            }
        }

        private int updateConsecutive(String condition) throws Exception {
            if (condition == null) {
                lastCondition.clear();
                consecutiveWindows.update(0);
                return 0;
            }
            String previous = lastCondition.value();
            int count = condition.equals(previous) ? consecutiveWindows.value() + 1 : 1;
            lastCondition.update(condition);
            consecutiveWindows.update(count);
            return count;
        }

        private boolean isWeekend(DayOfWeek dayOfWeek) {
            return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
        }
    }

    private static final class JsonWriter<T> extends RichMapFunction<T, String> {
        private transient ObjectMapper mapper;

        @Override
        public void open(Configuration parameters) {
            mapper = new ObjectMapper();
        }

        @Override
        public String map(T value) throws Exception {
            return mapper.writeValueAsString(value);
        }
    }

    public static final class TelemetryEvent implements Serializable {
        public String transformerId;
        public String meterId;
        public long eventTimeEpochMs;
        public double loadKw;
        public double ratedCapacityKw;

        public TelemetryEvent() {}

        public TelemetryEvent(
                String transformerId, String meterId, long eventTimeEpochMs, double loadKw, double ratedCapacityKw) {
            this.transformerId = transformerId;
            this.meterId = meterId;
            this.eventTimeEpochMs = eventTimeEpochMs;
            this.loadKw = loadKw;
            this.ratedCapacityKw = ratedCapacityKw;
        }
    }

    public static final class ThresholdModel implements Serializable {
        public String transformerId;
        public String dayType;
        public int hourOfDay;
        public double averageLoadKw;
        public double p95LoadKw;
        public String modelVersion;

        public ThresholdModel() {}

        public ThresholdModel(
                String transformerId,
                String dayType,
                int hourOfDay,
                double averageLoadKw,
                double p95LoadKw,
                String modelVersion) {
            this.transformerId = transformerId;
            this.dayType = dayType;
            this.hourOfDay = hourOfDay;
            this.averageLoadKw = averageLoadKw;
            this.p95LoadKw = p95LoadKw;
            this.modelVersion = modelVersion;
        }

        public String key() {
            return key(transformerId, dayType, hourOfDay);
        }

        public static String key(String transformerId, String dayType, int hourOfDay) {
            return transformerId + "|" + dayType + "|" + hourOfDay;
        }
    }

    public static final class TransformerWindow implements Serializable {
        public String transformerCode;
        public long windowStartEpochMs;
        public long windowEndEpochMs;
        public double currentLoadKw;
        public double ratedCapacityKw;

        public TransformerWindow() {}

        public TransformerWindow(
                String transformerCode,
                long windowStartEpochMs,
                long windowEndEpochMs,
                double currentLoadKw,
                double ratedCapacityKw) {
            this.transformerCode = transformerCode;
            this.windowStartEpochMs = windowStartEpochMs;
            this.windowEndEpochMs = windowEndEpochMs;
            this.currentLoadKw = currentLoadKw;
            this.ratedCapacityKw = ratedCapacityKw;
        }
    }

    public static final class TransformerMetric implements Serializable {
        public String transformerCode;
        public long windowStartEpochMs;
        public long windowEndEpochMs;
        public double currentLoadKw;
        public double ratedCapacityKw;
        public double loadRate;
        public Double historicalUpperKw;
        public String status;
        public int consecutiveAbnormalWindows;
        public String modelVersion;

        public TransformerMetric() {}

        public TransformerMetric(
                String transformerCode,
                long windowStartEpochMs,
                long windowEndEpochMs,
                double currentLoadKw,
                double ratedCapacityKw,
                double loadRate,
                Double historicalUpperKw,
                String status,
                int consecutiveAbnormalWindows,
                String modelVersion) {
            this.transformerCode = transformerCode;
            this.windowStartEpochMs = windowStartEpochMs;
            this.windowEndEpochMs = windowEndEpochMs;
            this.currentLoadKw = currentLoadKw;
            this.ratedCapacityKw = ratedCapacityKw;
            this.loadRate = loadRate;
            this.historicalUpperKw = historicalUpperKw;
            this.status = status;
            this.consecutiveAbnormalWindows = consecutiveAbnormalWindows;
            this.modelVersion = modelVersion;
        }
    }

    public static final class RiskAlert implements Serializable {
        public String eventId;
        public String transformerCode;
        public String alertType;
        public String severity;
        public double currentLoadKw;
        public double ratedCapacityKw;
        public double loadRate;
        public Double historicalUpperKw;
        public int consecutiveWindows;
        public long eventTimeEpochMs;

        public static RiskAlert from(TransformerMetric metric, String alertType, int consecutiveWindows) {
            RiskAlert alert = new RiskAlert();
            alert.eventId = UUID.randomUUID().toString();
            alert.transformerCode = metric.transformerCode;
            alert.alertType = alertType;
            alert.severity = "OVERLOAD".equals(alertType) ? "HIGH" : "MEDIUM";
            alert.currentLoadKw = metric.currentLoadKw;
            alert.ratedCapacityKw = metric.ratedCapacityKw;
            alert.loadRate = metric.loadRate;
            alert.historicalUpperKw = metric.historicalUpperKw;
            alert.consecutiveWindows = consecutiveWindows;
            alert.eventTimeEpochMs = metric.windowEndEpochMs;
            return alert;
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String normalizeJson(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").trim();
    }
}
