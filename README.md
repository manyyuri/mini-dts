# mini-dts

MySQL → MySQL 迁移同步引擎（对标阿里 OMS 架构的 mini 实现）：全量分片迁移 + binlog 增量同步 + 断点续传 + 幂等回放 + CRC 校验。

## 模块

| 模块 | 职责 |
|---|---|
| `mini-dts-common` | RowRecord 数据契约、RowCodec 归一化编码、主键解析/边界扫描、断点与回调端口 |
| `mini-dts-connector` | MySqlSink 幂等批量写入（ON DUPLICATE KEY UPDATE + 连续段分组建批）、位点/死信表 |
| `mini-dts-full` | 全量迁移：主键分片 + chunk 断点续传 + 并行 worker + 令牌桶限速 |
| `mini-dts-store` | binlog 增量：自解析 binlog（mysql-binlog-connector-java）、GTID 断点、事务聚合 |
| `mini-dts-verifier` | CRC chunk 对账，漂移二次重读防误报 |
| `mini-dts-cm` | 控制面：任务元数据（H2）、状态机、REST API |
| `mini-dts-starter` | Spring Boot 装配 + 编排（MigrationEngineImpl）+ Docker 门控的端到端 IT |

## 红线 → 代码

| 红线 | 实现 |
|---|---|
| R1 双端共用 RowCodec | `common/RowCodec.java`（全量/增量/校验唯一编码出口） |
| R2 位点表在目标库 | `connector/TargetSchemaInit`（dts_incr_checkpoint 建目标库） |
| R3 一致性位点而非缓存 binlog | `common/MasterStatusReader`（全量前记 gtid_executed） |
| R4 数据+位点同事务 | `MySqlSink.doWrite`（一个事务里先写数据再推位点） |
| R5 保序 + PK 更新拆删增 | `MySqlSink.splitRuns` + `BinlogRecordMapper.update` |
| R6 校验复用全量分片算法 | `verifier/VerifyService` 用同一个 `JdbcPkBoundaryScanner` + chunkSize |

## 快速开始

```bash
mvn test                                   # 42 个单测（H2，无需 Docker）
mvn test -pl mini-dts-starter              # 无 Docker 时 E2E 自动跳过
mvn -pl mini-dts-starter spring-boot:run   # 起服务 :8080

# 建任务（源库需 binlog_format=ROW, binlog_row_image=FULL, gtid_mode=ON）
curl -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' -d '{
  "sourceUrl":"jdbc:mysql://src:3306/shop","sourceUser":"u","sourcePassword":"p",
  "targetUrl":"jdbc:mysql://tgt:3306/shop","targetUser":"u","targetPassword":"p",
  "schema":"shop","tables":["orders"],"chunkSize":1000,"parallelism":4,"rowsPerSecond":50000}'

curl -X POST localhost:8080/api/tasks/{id}/start    # INIT → 全量 → 自动进增量
curl -X POST localhost:8080/api/tasks/{id}/pause    # 暂停（断点保留）
curl -X POST localhost:8080/api/tasks/{id}/resume   # 断点续传
curl -X POST localhost:8080/api/tasks/{id}/verify   # CRC 对账
curl -X POST localhost:8080/api/tasks/{id}/finish
curl localhost:8080/api/tasks/{id}/metrics
```

## 断点续传语义

- **全量**：数据先写目标库成功 → 再记 chunk 断点；崩溃恢复后重做"已写未记"的分片，靠幂等 upsert 收敛（H2 测试 `FullTransferH2Test.crashMidWayThenResumeEndsConsistent` 精确模拟该崩溃点）。
- **增量**：从目标库 `dts_incr_checkpoint` 位点重播（至少一次），Sink 幂等 → exactly-once 效果。
- **暂停→恢复**：增量恢复点 = 目标库位点；全量恢复 = 跳过已断点 chunk。

## 已知局限（demo 范围）

- 单源实例假设：GtidState 按 sid 记 max gno，多源写同一实例需完整并集语义
- 复合主键、无主键表明确拒绝（建任务时失败，不是运行中炸）
- 校验期间源在写的行会漂移：二次重读过滤，仍不同才报差异
- 目标端超出源最大主键的多余行单独兜底上报
- DDL 变更不处理（任务运行中加列会因列元数据缺失显式报错）

## 测试

- 42 个单测全绿（H2 MySQL 模式双库模拟源/目标）
- `EndToEndDockerTest`：真实 MySQL×2 容器的端到端验收（全量→增量→暂停→恢复→校验），无 Docker 自动跳过
