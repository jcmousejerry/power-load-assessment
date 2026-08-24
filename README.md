# LoadFlex Hub：异构负荷资源集群预测与调节潜力评估平台

这是一个已经能够在本机完整运行的前后端项目。项目不使用 Docker，直接使用 Windows 中已经安装的 MySQL、Redis、Kafka 和 MinIO。

## 1. 项目实现了什么

用户可以完成下面的完整操作：

1. 使用账号登录。
2. 上传 CSV 或 Excel 电力负荷文件。
3. 指定用户编号、时间、负荷值、单位和采样间隔对应的字段。
4. 为数据集创建完整分析流水线：数据质量 → 用户特征 → 用户聚类 → 各集群预测与基线 → 各集群调节潜力。
5. 查看任务当前排队位次、预计完成时间、执行阶段和进度。
6. 通过 WebSocket 实时收到任务成功或失败通知。
7. 查看分析结果、预测曲线、聚类曲线、基线曲线和潜力结果。
8. 取消排队或运行中的任务。
9. 对执行失败的任务进行重试，最多执行三次。
10. 从 12 台变压器设备库中自由添加或删除实时监控对象。
11. 对照查看每台受监控变压器的四块电表原始采集值与 Flink 窗口聚合结果。
12. 查看持续刷新的负荷趋势，叠加额定容量、Spark 历史 P95 和采集端最新点。
13. 通过 Flink 识别连续异常升高和容量过载，并查询预警记录。
14. 从实时监控直接选择异常设备，一键分析设备暂时不可用后可能影响的区域和负荷。
15. 自动寻找并比较备用供电方向，用简单结论和拓扑图说明哪些方向安全、哪些方向可能过载。
16. 对安全备用方案进入手动仿真调控台，按顺序隔离异常设备、接通备用线路、转移负荷并实时查看拓扑与负载变化。
17. 使用“AI异常处置 Agent”建立任务清单，自动查实时指标、趋势、预警、拓扑并调用候选生成、仿真和安全核验工具。
18. 经一次性授权后由 Agent 自主创建正式处置任务、跑完全部路径仿真、选择最低风险方案，并亲自完成五步仿真调控与结果核验。
19. 持久化每次 Agent 步骤、Token、审批、仿真进度、调控动作和操作前后状态，支持刷新恢复和取消。

## 2. 实际使用的技术

- Java 17
- Spring Boot 2.7.18
- Spring Security + JWT
- MyBatis-Plus，不使用 JPA
- Kafka 3.7.0
- Spark 2.4.7（Scala 2.11，历史动态阈值批处理）
- Flink 1.9.0（实时窗口聚合与连续异常判断）
- MySQL 8
- Redis
- MinIO
- Python 3.10
- FastAPI、Pandas、NumPy、Scikit-learn
- React 18、TypeScript、Vite、Ant Design、ECharts

## 3. 项目目录

| 目录 | 内容 |
| --- | --- |
| `loadflex-common` | Java 公共实体、Mapper、Kafka 消息对象 |
| `loadflex-server` | 登录、数据集、任务、结果、WebSocket、实时遥测归档和 Spark 周期调度服务 |
| `loadflex-consumer` | Kafka 任务消费者，负责调用 Python 并保存结果 |
| `algorithm-python` | 六类数据分析和预测算法 |
| `grid-spark-job` | 从 ClickHouse 批量读取固定历史与最新实时遥测；先将高频实时数据按电表和小时降采样，再计算分时 P95 阈值、写回 ClickHouse并直接发布 Kafka |
| `grid-flink-job` | 消费 Kafka 实时遥测，汇总变压器负荷并输出指标和预警 |
| `frontend` | React 管理界面 |
| `database` | MySQL 建表脚本，包含表注释和字段注释 |
| `scripts` | Windows 初始化、构建、启动、停止和验收脚本 |
| `sample-data` | 演示负荷数据生成程序 |
| `docs` | 详细设计和通俗说明文档 |

## 4. 本机环境路径

项目默认使用以下本机路径：

- Python：`D:\anaconda\envs\self_env_2\python.exe`
- Kafka：`D:\kafka_2.13-3.7.0`
- MinIO：`D:\java_learning\minio`
- MySQL 用户：`root`
- MySQL 密码：`123456`
- JDK 8：`D:\ONLY_ENGLISH_DIR\install\jdk-8`
- Hadoop：`D:\ONLY_ENGLISH_DIR\install\hadoop-3.1.0`
- Spark：`D:\ONLY_ENGLISH_DIR\install\spark-2.4.7-bin-hadoop2.7`
- Flink：`D:\ONLY_ENGLISH_DIR\install\flink-1.9.0`
- ClickHouse：`192.168.167.134:8123`，开发账号 `loadflex/123456`，数据库 `loadflex_grid`

MySQL 和 Redis 需要先处于运行状态。Kafka、ZooKeeper 和 MinIO 可以由项目脚本启动。

## 5. 一次完成初始化、构建和启动

在项目根目录打开 PowerShell，执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Start-All.ps1
```

这个命令可以重复执行：

- 如果四个项目应用都已经运行，脚本会直接提示项目已启动，不会重新构建。
- 普通启动使用快速增量模式：Java 源码没有变化时直接复用 JAR，React 由 Vite 开发服务器按需编译。
- 开发过程中只重启应用、保留 Kafka 等基础设施，执行 `.\scripts\Start-All.ps1 -RestartApplications`。
- 如果需要强制完整重启，执行 `.\scripts\Start-All.ps1 -Restart`。
- 如果还需要执行 Java/Python 测试和 React 生产构建，增加 `-FullBuild`。
- 默认会为 ZooKeeper、Kafka、MinIO、Python、两个 Java 服务和 React 前端分别打开日志窗口。
- 日志窗口中会持续显示该服务的新日志，日志也会同时保存到 `runtime/logs` 目录。
- 关闭日志窗口不会停止服务；请使用停止脚本关闭项目。

如果服务已经运行，只想重新打开全部日志窗口，可以执行：

```powershell
.\scripts\Show-All-Logs.ps1
```

该脚本会依次执行：

1. 创建或更新 `loadflex_hub` 数据库和项目表。
2. 启动 ZooKeeper、Kafka 和 MinIO。
3. 创建离线任务和实时预警使用的 Kafka Topic。
4. 仅在 Java 源码比 JAR 新时增量构建 Java 项目。
5. 复用前端依赖，由 Vite 开发服务器按需编译页面。
6. 启动 Python，再并行启动两个 Java 服务和 React 前端。
7. 连接 VM ClickHouse，复用或初始化 12 台变压器的 35 天基础历史遥测；本地 Spark 读取基础历史与最近 7 天实时遥测，计算分时 P95 阈值，并由 Spark 自己完成 ClickHouse 持久化和 Kafka 发布。
8. 使用独立 JDK 8 启动 Flink 实时预警作业；Java 主服务持续产生演示遥测，将 Kafka 原始明细成批归档到 ClickHouse，并每 30 分钟再次触发 Spark 更新阈值。
9. Flink 的窗口指标和预警通过 Kafka 交给 Java 写入 MySQL；连续异常首次确认后生成预警，异常持续期间按固定窗口间隔再次提醒，避免用户中途加入监控时漏掉仍在持续的异常。窗口指标默认保留 7 天，每位用户的预警最多保留最新 50 条且不超过 7 天，服务启动时和每小时清理旧记录。

启动完成后访问：

- 前端：http://127.0.0.1:5173
- Java 主服务：http://127.0.0.1:8080
- Java 消费服务：http://127.0.0.1:8081
- Python 算法服务：http://127.0.0.1:8000
- MinIO 控制台：http://127.0.0.1:9001

初始化账号（初始密码均为 `123456`）：

- `admin`：管理员。
- `analyst01`、`analyst02`：分析人员，可以管理各自的数据集和任务。
- `viewer01`：只读人员。

新增功能入口：

- “异常影响与备用供电”：一键得到安全备用路径后，可在拓扑图中亲手完成五步备用供电切换演练。
- “AI 异常处置 Agent”：模型通过原生工具调用逐轮决定下一步；经授权后完成正式仿真、最低风险方案选择和五步仿真调控。

详细操作步骤见 [异常仿真调控与 AI Agent 使用说明](docs/13-maintenance-planning-and-agent-user-guide.md)。

应用启动后可运行 `powershell -ExecutionPolicy Bypass -File .\scripts\Test-MaintenancePlanning.ps1`，自动验证拓扑、候选方案、持久化仿真、提交审核，以及 Agent 审批后自主完成五步调控并核验通过的完整链路；只验证计划功能时可追加 `-SkipAgent`。

Agent 固定使用 `qwen3.7-plus-2026-05-26`。优先从环境变量 `DASHSCOPE_API_KEY`、
`BAILIAN_WORKSPACE_ID` 和可选的 `BAILIAN_BASE_URL` 读取百炼配置；本地开发时也会读取
`bailian-docs` 中未纳入 Git 的密钥资料。密钥不会返回前端或写入日志。

## 6. 常用脚本

只初始化数据库：

```powershell
.\scripts\Initialize-Database.ps1
```

清空项目历史数据但保留用户账号：

```powershell
.\scripts\Clear-ProjectData.ps1 -Force
```

该命令只清理 `loadflex_hub` 中的数据集、任务、结果等业务记录、MinIO 的 `loadflex` bucket 和 `loadflex:*` Redis 键，不处理其他数据库或 bucket。

只启动 Kafka、ZooKeeper 和 MinIO：

```powershell
.\scripts\Start-Infrastructure.ps1
```

快速增量构建准备：

```powershell
.\scripts\Stop-Applications.ps1
.\scripts\Build-Project.ps1
```

提交前执行完整格式检查、测试和生产构建：

```powershell
.\scripts\Build-Project.ps1 -Full
```

Windows 中运行中的 Java 进程会占用 JAR 文件，因此重新构建前需要先停止项目应用。构建完成后再执行 `Start-Applications.ps1`。

只启动项目应用：

```powershell
.\scripts\Start-Applications.ps1
```

只停止前端、Java、Python 和 Flink，保留基础服务：

```powershell
.\scripts\Stop-Applications.ps1
```

停止本项目脚本启动的全部进程：

```powershell
.\scripts\Stop-Project.ps1
```

停止脚本只会结束 PID 记录与监听端口同时匹配的项目进程，不会结束 MySQL、Redis 或其他无关的 Java、Python、Node 进程。

## 7. 执行完整验收

服务启动后执行：

```powershell
.\scripts\Test-EndToEnd.ps1
```

验收脚本会真实检查：

- 管理员登录。
- CSV 上传到 MinIO。
- 字段映射保存到 MySQL。
- 六类任务进入数据库和 Kafka。
- Python 完成六类计算。
- 任务状态和结果写回 MySQL。
- 预测结果写入 Redis。
- WebSocket 收到任务成功通知。

实时变压器预警链路另行执行：

```powershell
.\scripts\Test-GridMonitoring.ps1
```

该脚本会验证 12 台变压器设备库、用户添加/删除监控对象、Kafka 原始遥测批量进入 ClickHouse、Spark 动态阈值、Flink 窗口指标、T001 容量过载、T002 历史异常升高以及 T003 无误报。演示环境使用 10 秒窗口、要求连续 3 个异常窗口，并在异常持续期间每 6 个窗口再次提醒；生产配置可将窗口和提醒间隔按实际需要调大。

实时链路使用独立 Topic：

- `grid-telemetry-raw`：电表模拟器产生的实时负荷。
- `grid-risk-threshold`：Spark 完成批量计算后直接发布的最新分时段 P95 动态阈值。
- `grid-transformer-metric`：Flink 生成的变压器实时窗口指标。
- `grid-risk-alert`：Flink 确认连续异常后生成的预警。

实时数据保留策略：

- `transformer_realtime_telemetry` 保存每块电表的原始采集明细，`transformer_realtime_load` 可按同一采样时刻查看变压器汇总负荷；ClickHouse 自动删除 7 天前的实时明细。
- `transformer_risk_threshold` 保存每次 Spark 模型版本，ClickHouse 自动删除 7 天前的阈值版本。
- Java 每 30 分钟触发一次 Spark；可用 `GRID_SPARK_INTERVAL_MS` 调整周期，设 `GRID_SPARK_SCHEDULER_ENABLED=false` 可关闭周期任务。
- 初次和周期 Spark 的完整输出会追加到 `runtime/logs/grid-spark-scheduler.log`，启动脚本会打开独立日志窗口；日志达到 10 MB 后只保留一个轮转文件。
- 四个电网 Kafka Topic 同时设置 7 天时间上限和按分区字节上限，避免 Broker 日志无限增长。

## 8. 数据异构具体体现在哪里

系统并不是只接受一种固定文件格式。

- 支持长表：每一行包含用户、时间和负荷值。
- 支持宽表：每一行包含日期，`P1` 到 `P96` 等字段表示一天内的多个采样点。
- 原始字段名可以不同，由用户保存字段映射。
- 负荷单位可以是 kW、MW 或 kWh，算法计算前统一转换为 kW。
- 采样间隔可以不同，系统会读取用户指定的间隔，也可以从时间数据中计算间隔。
- 不同用户的负荷大小、峰值时间、峰谷差和稳定程度不同，特征提取和聚类会分析这些行为差异。

字段映射不是填写标准字段值，而是告诉系统“上传文件中的哪一列承担什么含义”：

- `userColumn`：用电单元唯一编号列，必须至少包含两个不同值，例如 `user_id`、`meter_id`、`用电单元编码`。
- `timeColumn`：长表的完整采样时间列，例如 `timestamp`；宽表留空。
- `valueColumn`：长表的负荷数值列，例如 `load_kw`；宽表留空。
- `dateColumn`：宽表的日期列，例如 `data_date`；长表留空。
- `unit`：原始数值单位。`MW` 会乘 1000 转为 kW，`kWh` 会结合采样间隔换算成平均 kW。
- `intervalMinutes`：相邻负荷点间隔；15分钟对应每天96点，30分钟对应每天48点。

`sample-data` 提供三份多用电单元数据：标准长表、自定义中文字段长表和 `p1...p96` 宽表。各文件的精确映射见 [sample-data/README.md](sample-data/README.md)。用户聚类现在只允许 `sampleKind=USER`；如果数据或映射只能识别出一个用电单元，聚类任务会明确失败，不再自动改成按天聚类。

## 9. 前方任务数量和预计排队时间怎样计算

任务按照计算资源分为普通 CPU 和高内存 CPU 两类。用户提交任务后，主服务只创建数据库记录并写入对应资源池的 Redis ZSET，任务此时不会直接进入 Kafka。

调度器每 500 毫秒检查一次资源池。资源池空闲时，调度器取出 ZSET 队首任务，把数据库状态改为 `RUNNING`，并在同一数据库事务中创建 Outbox 消息。事务提交后任务才从等待队列移入运行集合，Outbox 程序随后把任务发送到 Kafka。

查询某个等待任务时不统计数据库，也不预先重算整个队列：

1. 使用 Redis `ZRANK` 直接得到该任务前方的任务数量。
2. 读取当前运行任务的预计剩余时间。
3. 读取该任务前方任务的预计耗时并累加。
4. 两部分之和就是该任务的预计排队时间；当前时间加预计排队时间就是预计开始时间。
5. 任务耗时使用最近 100 次同类型成功任务的历史值；历史不足时使用任务类型默认值。

只有上游依赖全部成功的任务才会进入排队状态；等待上游的任务不会占用队列位次或计算资源。上游失败时，对应下游会标记为“上游失败，未执行”。不同数据集的任务和中间结果相互隔离。

不同流水线之间共享资源池，因此会相互竞争同一资源池的执行顺序。`PROFILE`、`FEATURE`、`BASELINE`、`POTENTIAL` 使用 `CPU_LIGHT`，`CLUSTER`、`FORECAST` 使用 `CPU_HIGH_MEM`。每个资源池当前配置一个并发槽：同池任务按优先级、入队时间、任务 ID 排队；两个资源池可以并行执行。流水线依赖只决定任务何时具备入队资格，不会为某条流水线预留资源。

为了便于本地观察排队效果，Python 算法服务为六类任务增加了 3～6 秒的可配置演示耗时。设置环境变量 `ALGORITHM_DEMO_DELAY_MULTIPLIER=0` 可以关闭，设置为 `2` 可以加倍；真实生产环境应使用实际计算耗时而不是演示延迟。

任务中心直接显示“前方任务数量、预计排队时间、预计开始时间”。任务状态变化通过用户专属 WebSocket 主题推送；排队信息由前端三秒轮询按需刷新。

MySQL 仍是任务状态的正式记录。Java 启动时会从 MySQL 重建 Redis 队列，之后默认每五分钟进行一次低频对账，用于修复 Redis 重启或短暂异常造成的不一致。这个对账只读取活动任务，不会像旧实现一样每两秒扫描并逐条更新全部排队任务。

系统支持多用户同时排队。位次按共享资源池中的全部用户任务统一计算，但普通用户只能查看自己的任务，并且 WebSocket 鉴权会阻止订阅其他用户的通知；管理员可以查看全局任务。

### 数据源和流水线怎样隔离

一次“创建完整分析流水线”会先在 `analysis_pipeline` 生成独立记录，同一次创建产生的质量检查、特征、聚类、预测、基线和潜力任务都保存相同的 `pipeline_id`。

任务中心和分析结果页先选择数据源，再选择该数据源下的一次流水线。后端同时使用 `dataset_id` 和 `pipeline_id` 过滤，因此：

- 不同数据源的任务和结果不会混在一起。
- 同一数据源重复运行两次分析，也能按两次流水线分别查看。
- 升级前的旧任务没有 `pipeline_id`，统一放在对应数据源的“历史未分组”入口，不根据任务名称猜测归属。

## 10. Kafka、Outbox 和重复消息处理

创建任务时只保存任务记录并加入 Redis 等待队列。调度器确认资源空闲后，才在同一个 MySQL 事务中把任务改为运行态并创建 Outbox 记录。事务成功后，定时发送程序把 Outbox 内容发送到 Kafka。发送失败时会重试，最多 20 次。

Kafka 消费者收到消息后，先把消息编号写入 `consumed_event`。相同消息再次到达时不会再次执行算法。任务重试会生成新的消息编号，因此可以正常执行下一次尝试。

## 11. Redis 缓存

排队相关数据使用以下 Redis 结构：

- `loadflex:schedule:queue:{资源池}`：ZSET。member 为 `反转优先级:入队毫秒时间:任务ID`，score 固定为 0；同分值时按 member 排序，得到“优先级高、入队早、ID小”的稳定顺序。
- `loadflex:schedule:running:{资源池}`：ZSET。member 为任务 ID，score 为预计结束时间的 Unix 毫秒值；集合非空表示资源池正被占用。
- `loadflex:schedule:task-members`：Hash。field 为任务 ID，value 为 `资源池|等待队列member`，用于任务开始、取消或结束时快速从正确的 ZSET 删除。
- `loadflex:schedule:task-pools`：Hash。记录活动任务所属资源池，用于快速清理运行状态。
- `loadflex:schedule:task-runtimes`：Hash。保存每个活动任务的预计耗时和可信度，查询时只读取目标任务前方的数据。

上述 Redis 数据都是可从 MySQL 重建的实时索引，不是任务和分析结果的正式存储。

用户打开负荷预测结果详情时，Java 首先读取 Redis。第一次查询没有缓存时从 MySQL 读取，并把预测结果保存到 Redis 10 分钟。之后查询相同预测结果时直接读取 Redis。

缓存键格式：

```text
loadflex:result:forecast:{结果编号}
```

这个键使用 Redis String，value 是序列化后的 `analysis_result` JSON，不属于排队 ZSET 或 Hash。

## 12. 分析结果保存在哪里

每个成功任务会同时保存两部分结果：

- MySQL `analysis_result.summary_json`：保存页面绘图和指标展示需要的结果摘要；同一行还保存任务 ID、结果类型、算法版本、MinIO 对象路径和文件校验值。
- MinIO `results/{taskId}/{taskType小写}/result.json`：保存算法服务生成的完整结果文件。`analysis_result.artifact_object_key` 只保存这个文件在 MinIO 中的位置。

原始 CSV/Excel 文件也保存在 MinIO。MySQL 负责状态、关系、查询条件和小型摘要，MinIO 负责原始文件及完整结果文件。

权限检查不会因为使用缓存而省略。即使 Redis 中有结果，Java 仍会检查当前用户是否有权访问该任务。

## 12. 权限

- `ADMIN`：可以查看全部用户的数据，并可以上传、创建任务、取消和重试。
- `ANALYST`：只能访问自己的数据，可以上传、创建任务、取消和重试。
- `VIEWER`：只能查看自己有权访问的数据，不能修改数据或创建任务。

WebSocket 连接同样需要 JWT。用户只能订阅自己的任务通知地址，不能通过修改前端地址订阅其他用户的通知。

## 13. Java 代码格式

项目已经配置自动格式整理和格式检查：

- 注解必须单独一行。
- 判断和循环必须使用大括号。
- 一个代码行不能放多条语句。
- 禁止星号 import。
- 单行最长 120 个字符。
- 使用 4 个空格缩进。

执行 `mvn verify` 时会自动检查，格式不符合要求时构建失败。

## 14. 第一版明确边界

当前版本完整实现 CSV/Excel 离线负荷分析闭环，以及基于演示遥测的变压器实时异常负荷预警。真实电表协议接入、天气数据、Kubernetes、自动扩容、复杂工作流和完整模型管理平台不属于当前版本。

如果第一次阅读项目，建议继续查看 [完整易懂版说明](docs/10-plain-language-guide.md)。

如果需要逐字段理解数据库，请查看 [数据库每张表和每个字段的通俗说明](docs/12-database-tables-plain-guide.md)。
