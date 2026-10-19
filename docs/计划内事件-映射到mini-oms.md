# 「计划内事件」→ mini-oms 模块映射

> 对照：oms-console 前端 `src/pages/Mirror/PlannedEvents`（18 文件 / 6093 行）+
> 后端 `CloudOperatorPlannedEvent*Controller`、`OcpMessageCenterController`、`ReleaseProduct/WorkerInstance/Store/Subscribe`
> 目标：`/Users/lijuying/techLearn/mini-oms`（7 模块，86 个 java 文件，HEAD 工作树）

## 先说清性质差异（决定映射怎么读）

| 层面 | oms-console「计划内事件」 | mini-oms |
| --- | --- | --- |
| 是什么 | **控制面的运维编排 + 通知层**：谁在什么时间被升级、升级前通知客户、升级期任务怎么处置 | **数据面 + 最小控制面**：任务怎么迁、断点怎么续、怎么不丢不重 |
| 有没有「事件」实体 | 有（PlannedEvent / EventDefinition / Task 三层 + OCP 消息） | **完全没有**（全仓无 upgrade/version/notify 相关文件） |
| 所以映射分两类 | ① **事件的影响对象** → mini-oms 存量模块（一一对应）② **事件本身** → mini-oms 缺口（需新增） | |

## 一、事件「影响对象」→ 存量模块（高置信映射）

OMS 侧的 5 种升级组件与 3 种关联任务，在 mini-oms 里都能找到对应的代码实体：

| OMS 概念（计划内事件里） | mini-oms 落点 | 对应关系强度 |
| --- | --- | --- |
| `STORE` 组件升级 | `mini-oms-store`：`BinlogStoreService`（自解析 binlog、GTID 断点、断线重连） | 强：Store = 增量抓取侧 |
| `CONNECTOR` 组件升级 | `mini-oms-connector`：`MySqlSink` + `TargetSchemaInit` + `TargetFullCheckpointStore` | 强：Connector = 写入 + 位点自用表 |
| `CHECKER_VERIFY` 组件升级 / 任务类型 `VERIFY` | `mini-oms-verifier`：`VerifyService`（CRC chunk 对账、漂移二次重读） | 强：Checker = 校验 |
| `SUPERVISOR` 组件升级 | **无对应**（starter 只是编译期装配层，不是 supervisor，见第四节） | 无：mini-oms 没有「组件多进程」前提 |
| `SUBSCRIBE`（订阅形态） | `mini-oms-store`（订阅 = 增量链路的另一种形态，mini-oms 未拆出独立模块） | 弱：缺口在「订阅」这个产品形态 |
| 任务类型 `MIGRATION` / `SYNC` | `mini-oms-full` + `mini-oms-store`（全量 + 增量），元数据在 `cm/TaskMetadata` | 强 |
| 事件「到点自动执行」、升级期任务处置 | `cm/MigrationEngine.pause/resume/reattach` + `cm/TaskStateMachine` + `starter/TaskRecovery` | 强：升级前后要做的正是 pause → resume/reattach |
| 升级后必须能续跑（不丢不重） | `connector/TargetFullCheckpointStore` + `TargetSchemaInit`(位点同库同事务) + `common/GtidState`、`MasterStatusReader` | 强：这是「计划内升级」能落地的最硬前提 |
| 升级前预检查 / 能力矩阵 | `store/MySqlEnvChecker`（log_bin / ROW / FULL / GTID 四项） | 中：mini-oms 是最小预检，OMS 是能力矩阵 |
| 任务归属哪个节点（升级要停谁） | `cm/TaskLeaseManager`（租约 + 心跳 + 过期接管），`nodeId` 是 Worker 的最小雏形 | 中 |

## 二、事件「实体与通知」→ 缺口模块（mini-oms 没有，要新增）

| OMS 模块 | mini-oms 现状 | 若复刻应落在哪 |
| --- | --- | --- |
| `PlannedEvent` 事件实体（时间/原因/状态/云厂商地域/VPC） | 无。`cm/TaskMetadata` 是唯一 record 元数据，且**没有 taskType / projectId / region 字段** | `mini-oms-cm`：新增 `PlannedEvent` record + `oms_planned_event` 表（`SchemaInit` 里加 DDL，注意 H2/MySQL 双方言） |
| 事件状态机（待发送 → 发送中 → 已发送/失败/取消） | 无。但 `cm/TaskStateMachine` 是现成范式（纯状态机、无 IO、可穷举测试） | `mini-oms-cm`：新增 `EventState` + `ALLOWED` 表，照抄 `TaskStateMachine` 的写法 |
| 「关联的运维任务」查询 | 无关联关系；`cm/TaskController` 只有 `/api/tasks` 列表 + start/pause/resume/finish/verify/metrics | `mini-oms-cm`：`TaskMetadata` 加 `taskType`，`/api/tasks?eventId=` 加过滤 |
| 客户通知发送与通知状态 | 只有 `common/TaskStateNotifier`（onFullDone / onError / onSchemaChange）——**唯一现成的对外回调端口** | `mini-oms-common`：扩成 `PlannedEventNotifier`，沿用「default 方法降级为 ERROR，宁可明显也不静默」的既有策略 |
| 事件定义（事件码 ↔ 消息标识绑定） | 无 | `mini-oms-cm`（与事件同库，避免再引一个配置中心） |
| OCP 消息中心：消息分类 / 模板 / 渠道（站内信·邮件·短信）/ 中英日三语 | 无，且 mini-oms 明确不做 i18n、不接外部消息中心 | **不映射**：属于外部集成，超出 mini-oms「把决策点变成小实验」的边界 |
| Worker 实例注册与版本（ListWorkerInfos / ExploreWorkerVersions） | 无实例注册，只有 `starter/EngineProperties` 的实例配置 | 若复刻：`mini-oms-cm` 加 `oms_worker` 表（nodeId + version），`starter` 启动时注册 |
| 云厂商 / 地域（DescribeOpRegions） | 无 | 不映射（单集群玩具） |
| 发布计划构建（RebuildReleasePlanPlannedEvents） | 无 | 不映射；mini-oms 无「发布版本」概念 |

## 三、一句话总结（按 MISSION 目标 4「判断新需求落在哪几段」）

- **存量能承接的**：`store`（Store 组件）、`connector`（Connector 组件 + 位点）、`verifier`（Checker）、`cm`（任务状态与租约）、`starter`（进程编排与恢复）——即「**事件的影响面**」5 个模块里 4 个强对应。
- **缺口集中在一处**：`mini-oms-cm`（缺事件实体 + 事件状态机 + 任务类型关联）与 `mini-oms-common`（缺事件通知端口）；`starter` 只补一个「到点扫描」的 `@Scheduled`（`TaskRecovery` 已有心跳定时骨架可复用）。
- **最不该硬塞的**：OCP 消息中心、多云地域、发布计划构建——它们不是 mini-oms 想验证的决策点，强映射会让 toy 变成烂尾工程。

若真要在 mini-oms 复刻「计划内事件」最小版本：**cm（表 + 状态机 + 关联字段）+ common（通知端口）+ starter（定时扫描）3 个模块起步，store/connector/verifier 一行都不用改**——因为它们已经是「被升级的东西」，只需依赖既有 pause/resume/断点能力即可证明「升级期间不丢不重」。

## 四、勘误：mini-oms-starter ≠ OMS Supervisor

上一版把 `SUPERVISOR` 映射到 `starter`，标注「中」，**这个说法不严谨，应为「无对应」**。两者只共享「把东西拉起来并驱动」这一层语义，抽象层次完全不同：

| 维度 | `mini-oms-starter` | OMS Supervisor |
| --- | --- | --- |
| 形态 | **Maven 模块 / 编译期装配层**：`MiniOmsApplication`（Spring Boot 启动类）+ `EngineConfig`（@Bean 装配）+ `EngineProperties` | **独立守护进程**，以 RPM 装在 `oms_worker` 容器里（`oboms-supervisor-2.1.14.noarch.rpm`，`/home/ds/upgraderpm`） |
| 边界 | 单 JVM 进程内 | 跨进程 / 跨容器，worker 级 |
| 它「拉起」什么 | **Bean**：把 common/connector/full/store/verifier 的类 new 出来拼成运行时（`MigrationEngineImpl.Runtime`） | **进程**：拉起 / 守护 / 回收 worker 上的 Store、Connector、Full、Checker 进程 |
| 怎么接命令 | 同进程方法调用 | CM 通过 `InvokeFuncCommand` / `RequestHttpCommand` 经 `SupervisorForwardService` 转发（`biz` 模块 `InvokeFuncService`） |
| 自己是否升级对象 | 否，随 jar 一起发版 | **是**：`UpgradeModuleTypeEnum.SUPERVISOR` 有独立 `supervisorVersion`，升级走 rpm 下发 + restart（`build/cloud/omscloud/tools/run_ops.sh`） |
| 有无插件机制 | 无 | 有：`supervisor.plugin.dataflow.name`、`supervisor.plugin.enabled` |
| 配置中心 | `EngineProperties`（本地 yml） | `SupervisorConfig` / `ConfigEnum.supervisor.config`（region 级下发，可 `restartSupervisorTimeoutInSec` 超时控制） |

**结论**：mini-oms 里根本没有 supervisor 这一层，因为 mini-oms **不存在「组件拆成多进程」这个前提**——全量、增量、校验全是同 JVM 的库调用。要造一个 supervisor，得先把 store/connector/full 从 jar 里拆出来变成独立进程 + 定义 IPC 协议 + 进程守护与健康检查——这恰好就是 MISSION 五问里「**为什么组件要拆**」这个决策点，不是靠 starter 改名能替代的。

`starter` 里真正和 Supervisor **功能重叠**的只有两处，且都是弱重叠：
- `EngineProperties.nodeId`（host:pid）≈ Supervisor 的 worker 身份标识；
- `TaskRecovery` 启动恢复 + 续租心跳 ≈ Supervisor 重启后重新拉起组件 + 报活。

这两处能说明「角色有相似性」，但不能说「starter 就是 supervisor」。
