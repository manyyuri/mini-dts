# mini-dts

MySQL → MySQL 迁移同步引擎（对标阿里 OMS 架构的 mini 实现）：全量分片迁移 + binlog 增量同步 + 断点续传 + 幂等回放 + CRC 校验 + DDL 感知 + 多节点租约接管。

## 模块

| 模块 | 职责 |
|---|---|
| `mini-dts-common` | RowRecord 数据契约、RowCodec 编码、主键解析/边界扫描、JdbcUrl 库名校验、SchemaFingerprint 结构指纹、RateLimiter 限流、TargetAdmin/TaskStateNotifier 端口 |
| `mini-dts-connector` | MySqlSink 幂等批量写入（库名限定 + 连续段分组建批 + 语句切片）、目标库自用表（位点/死信/全量断点/DDL 台账/结构指纹）、TargetFullCheckpointStore |
| `mini-dts-full` | 全量迁移：主键分片 + chunk 断点续传 + 并行 worker + 两层限流 |
| `mini-dts-store` | binlog 增量：自解析 binlog、GTID 断点、事务聚合与内存上限、断线重连、DdlClassifier 分类处置 |
| `mini-dts-verifier` | CRC chunk 对账，漂移二次重读防误报 |
| `mini-dts-cm` | 控制面：任务元数据（H2 / 共享 MySQL 双方言）、状态机、任务租约、REST API |
| `mini-dts-starter` | Spring Boot 装配 + 编排（MigrationEngineImpl）+ 启动恢复 + 租约心跳 + Docker 门控 E2E |

## 红线 → 代码

| 红线 | 实现 |
|---|---|
| R1 双端共用 RowCodec | `common/RowCodec.java`（全量/增量/校验唯一编码出口） |
| R2 位点在目标库 | `connector/TargetSchemaInit`（dts_incr_checkpoint 与业务数据同库，才能同事务） |
| R3 一致性位点而非缓存 binlog | `common/MasterStatusReader`（全量前记 gtid_executed） |
| R4 数据+位点同事务 | `MySqlSink.doWrite`（一个事务里先写数据再推位点） |
| R5 保序 + PK 更新拆删增 | `MySqlSink.splitRuns` + `BinlogRecordMapper.update` |
| R6 校验复用全量分片算法 | `verifier/VerifyService` 用同一个 `JdbcPkBoundaryScanner` + chunkSize |
| R7 单任务单执行者 | `cm/TaskLeaseManager`（租约 + 心跳，跨节点排他） |
| R8 断点跟着数据走 | `connector/TargetFullCheckpointStore`（全量断点也在目标库，换节点能续跑） |
| R9 结构漂移必须显式 | `common/SchemaFingerprint` + `store/DdlClassifier`（DDL 台账 + 白名单 + 转人工） |
| R10 失败必须可见 | `BinlogStoreService`（重连退避 → 耗尽才 `onError`；绝不静默退出） |

## 快速开始

```bash
mvn test                                    # 71 个单测（H2，无需 Docker）
mvn test -pl mini-dts-starter               # 无 Docker 时 E2E 自动跳过
mvn -pl mini-dts-starter spring-boot:run    # 起服务 :8080
```

```bash
# 建任务（源库需 binlog_format=ROW, binlog_row_image=FULL, gtid_mode=ON）
# 注意：sourceUrl / targetUrl 的库名必须与 schema 一致（否则建任务即失败）
curl -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' -d '{
  "sourceUrl":"jdbc:mysql://src:3306/shop","sourceUser":"u","sourcePassword":"p",
  "targetUrl":"jdbc:mysql://tgt:3306/shop","targetUser":"u","targetPassword":"p",
  "schema":"shop","tables":["orders"],"chunkSize":1000,"parallelism":4,"rowsPerSecond":50000}'

curl -X POST localhost:8080/api/tasks/{id}/start    # INIT → 全量 → 自动进增量
curl -X POST localhost:8080/api/tasks/{id}/pause    # 暂停（断点保留，租约释放）
curl -X POST localhost:8080/api/tasks/{id}/resume   # 断点续传
curl -X POST localhost:8080/api/tasks/{id}/verify   # CRC 对账
curl -X POST localhost:8080/api/tasks/{id}/finish
curl localhost:8080/api/tasks/{id}/metrics          # 含 state / 租约持有者 / 增量位点 / 重连次数
```

## 断点续传语义

- **全量**：数据先写目标库成功 → 再记 chunk 断点（断点在目标库 `dts_full_checkpoint`）；崩溃恢复后重做"已写未记"的分片，靠幂等 upsert 收敛。
- **增量**：从目标库 `dts_incr_checkpoint` 位点重播（至少一次），Sink 幂等 → exactly-once 效果。
- **暂停→恢复**：增量恢复点 = 目标库位点；全量恢复 = 跳过已断点 chunk。
- **换节点/重启**：启动扫描把 DB 里 RUNNING_* 的任务重挂回来（`MigrationEngineImpl.reattach`），抢到租约才接管。

## DDL 处理

binlog 里的 DDL 走 QUERY 事件，分类后三路处置（`store/DdlClassifier`）：

| 分类 | 例子 | 处置 |
|---|---|---|
| IGNORE | `BEGIN` / `COMMIT` / `SET ...` | 忽略 |
| ADD_COLUMN（白名单） | `ALTER TABLE t ADD [COLUMN] c ...` | 自动在目标端执行 + 刷新元数据 + 更新指纹，任务继续 |
| OTHER_DDL | `MODIFY` / `DROP` / `RENAME` / `CREATE` / `TRUNCATE` / `ADD INDEX` | 记 `dts_ddl_event` + 任务转 PAUSED 等人工 |
| OTHER_STATEMENT | `GRANT` / `ANALYZE` / 存储过程 | 同上 |

另外建任务时会比对源/目标的表结构指纹，运行期每次（重）连都复核一次 —— 手工改结构这种 binlog 外的漂移也会被拦在写入之前。

## 多节点部署

```yaml
spring.datasource.url: jdbc:mysql://meta-host:3306/minidts_meta   # 元数据库换成共享库
minidts:
  node-id: node-1
  max-concurrent-tasks: 8
  global-rows-per-second: 200000     # 实例级总预算（任务级限速之外的兜底）
  lease-ttl: 30s
  lease-renew-interval-millis: 10000
  recover-on-startup: true
```

同一任务同一时刻只有一个节点在跑（`dts_task_lease`）；续租失败 = 被接管，本地运行时立即停手。断点全在目标库，接管方从断点续跑。

## 已知局限

- 单源实例假设：GtidState 按 sid 记 max gno，多源写同一实例需完整并集语义（单主库场景成立）
- 一份任务一个源库一个目标库同名（不支持改库名/多源合并/分库分表聚合）
- 复合主键、无主键表明确拒绝（建任务时失败，不是运行中炸）
- 校验期间源在写的行会漂移：二次重读过滤，仍不同才报差异
- 目标端超出源最大主键的多余行单独兜底上报
- DDL 白名单只覆盖纯追加列；其余 DDL 需要人工在目标端执行后 resume
- 组件之间仍是同进程 Java 接口（分包解耦，未做 RPC/MQ 传输层；多节点靠共享元数据库 + 租约）

## 测试

- 71 个单测全绿（H2 MySQL 模式双库模拟源/目标）：含 `DdlClassifierTest`、`TaskLeaseManagerTest`、`TargetStoreH2Test`、`SchemaFingerprintTest`、`GlobalRateLimiterTest`
- `EndToEndDockerTest`：真实 MySQL×2 容器的端到端验收 —— 主链路（全量→增量→暂停→恢复→校验）、ADD COLUMN 自动落地、破坏性 DDL 停任务并留台账、双节点租约排他、崩溃后新节点从目标库断点接管；无 Docker 自动跳过
