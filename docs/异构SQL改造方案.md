# 异构 SQL 改造方案（提案，未开工）

> 目标：让 mini-dts 支持「源/目标不是同一种数据库」的迁移。
> 本文只做**判断与设计**，不含已完成的实现 —— 先对齐三件事再动手，避免做错方言白干一周。

---

## 0. 先说结论

| 结论 | 内容 |
|---|---|
| 「异构」不是一个需求，是三个成本差 10 倍的需求 | 目标异构 / 源异构 / 双异构 + 语义自动转换 |
| 现在最该做的第一件事 | 把方言从 `MySqlSink` 里撕出来（纯重构，行为不变，现有 71 个测试全绿才算过） |
| 为什么先做它 | 它是唯一「无论最后接哪个目标库都必须做、且不会白做」的一步 |
| 最容易被低估的坑 | 不是 SQL 语法，是**校验的 CRC 口径**（同一个值在两种库里渲染不同 → 永远对不上） |

![方言缝合线](img/fig-d1-dialect-seam.png)

---

## 1. 先界定：你要的「异构」是哪一档

| 档 | 形态 | 需要动的东西 | 量级 |
|---|---|---|---|
| **A. 目标异构**（最常见） | 源 MySQL 不变 → 目标 PG / OceanBase-Oracle 模式 / 达梦 / 金仓 | 写入侧方言 + 类型映射 + 校验编码 | 中等，1~2 周 |
| **B. 源异构** | 源 PG / Oracle → 目标 MySQL | **新增一个 Store**（PG 逻辑复制 / Oracle LogMiner），binlog 那套全换 | 大，3~4 周 |
| **C. 双异构 + 类型语义自动转换** | 任意 → 任意，含类型/DDL 自动翻译 | A + B + 类型语义引擎 + DDL 翻译器 | 很大，按月算 |

**现在代码是 MySQL → MySQL。** 绝大多数实际诉求是 A 档（换个目标库）。B 档不是"支持异构 SQL"，是"重写变更抓取"。

---

## 2. 现在写死在哪（按文件定位，不是猜）

| 层 | 写死的位置 | 异构后会怎么坏 |
|---|---|---|
| 写入 SQL 拼装 | `connector/MySqlSink.java:194`（反引号）、`:122-128`（`VALUES(col)` + `ON DUPLICATE KEY UPDATE`） | PG 用双引号 + `ON CONFLICT … DO UPDATE SET x = EXCLUDED.x`；Oracle 没有 upsert，只能 `MERGE INTO` |
| 目标自用表 DDL | `connector/TargetSchemaInit.java:32`（`BIGINT AUTO_INCREMENT`）、`:31`（`LONGTEXT`/`CLOB`）、`:90-91`（`ON DUPLICATE` / `MERGE`） | 自增列、长文本、upsert 语法三家都不一样 |
| 边界扫描 | `common/JdbcPkBoundaryScanner.java:51`（反引号）、`LIMIT` 分页 | 引用符与分页语法不同 |
| 元数据查询 | `common/PkResolver.java`、`SchemaFingerprint.java`、`MigrationEngineImpl.listAllTables` | `INFORMATION_SCHEMA` 大致通用，但 `DATA_TYPE` 词汇表不同（`tinyint` vs `boolean`、`int unsigned` 在 PG 根本不存在） |
| 值归一化 / 比较 | `common/Values.java`、`RowCodec.java` | `unsigned`、`datetime(6)`、`timestamptz`、`json` vs `jsonb`、`bytea` |
| 校验 | `verifier/VerifyService.java`（CRC 基于 RowCodec） | 同一个值两边渲染不同 → CRC 永远不相等（最隐蔽的坑） |
| 源端变更抓取 | `store/BinlogStoreService.java`、`MySqlEnvChecker.java`（binlog/GTID/row_image） | 这一层是**源方言**，只有 B 档才需要动 |

---

## 3. 目标架构：一条缝合线

缝合线只画在「行 → SQL」这一层：

```
RowRecord（与方言无关，已在第 2 课确立）
      │
      ▼
SqlDialect（端口，四个职责）
  ├─ quote(identifier)                    标识符引用
  ├─ upsertSql(table, cols, pk)           幂等写法
  ├─ deleteSql(table, pks, n)             批量删除
  └─ adminDdl(...) / pagingClause(...)    自用表 DDL / 分页
      │
      ├─ MySqlDialect        `x`  + ON DUPLICATE KEY UPDATE
      ├─ PostgresDialect     "x"  + ON CONFLICT (pk) DO UPDATE SET x = EXCLUDED.x
      └─ OracleDialect       "X"  + MERGE INTO
```

好处是**改动被关在三个地方**：`Sink` 只保留「事务 + 批次 + 位点 + 死信」，新增目标库 = 新增一个方言类 + 一张类型映射表；全量、增量、校验三条通道完全不动。

---

## 4. 分四期落地（每期都有可验收的产物）

| 期 | 内容 | 验收标准 | 估时 |
|---|---|---|---|
| **期 1 抽方言** | 新增 `SqlDialect` 端口 + `MySqlDialect`，把 `MySqlSink` / `TargetSchemaInit` / `JdbcPkBoundaryScanner` 里的 SQL 生成全部搬进去 | **行为零变化**：现有 66 个可执行测试全绿，`MySqlSink` 不再含任何字面 SQL 片段 | 1~2 天 |
| **期 2 加 PG 方言** | `PostgresDialect` + 类型映射表（`tinyint(1)→boolean`、`datetime→timestamp`、`unsigned→更大整型`、`json→jsonb`…）+ H2(PG 模式) 测试 | 同一批 `RowRecord` 在两种方言下生成的 SQL **语义等价**（用一个矩阵测试：upsert / delete / 多行 / 特殊类型） | 2~3 天 |
| **期 3 校验跨方言** | canonical 编码升级（值 → 与库无关的规范字节），结构指纹改成「类型等价类」比较 | 同一份数据在 MySQL 与 PG 上算出的 CRC 相同；`BIGINT` 与 `bigint` 视为同一指纹 | 2~3 天 |
| **期 4 DDL 翻译（可选）** | 从源 `information_schema` 反向生成目标 `CREATE TABLE`；`ADD COLUMN` 事件跨方言翻译 | 目标表可由系统自动建出；跨方言加列不中断同步 | 3~5 天 |
| 期 5 源异构（另立项） | 新 Store：PG 逻辑复制槽 / Oracle LogMiner | 与期 1~4 独立 | 3~4 周 |

> 期 1 的价值：它把「以后接任何库」从「改写入端」变成「加一个类」。**即使最后不接第二个库，期 1 也让代码更干净**，所以风险最低。

---

## 5. 硬骨头（按踩坑概率排序）

| # | 坑 | 为什么难 | 缓解 |
|---|---|---|---|
| 1 | **upsert 语义差异** | MySQL 冲突键可以是任何唯一键；PG 必须显式写冲突目标列；Oracle 无 upsert，只能 `MERGE`（且有并发竞态） | 方言接口里 `upsertSql` 显式接收主键列；Oracle 走 `MERGE + 唯一键` |
| 2 | **校验 CRC 跨库** | `1.0`/`1.00`、`2026-01-01 00:00:00.000`、`0x01`、`true`/`1` 在不同库渲染不同 | canonical 编码层统一（期 3 的核心） |
| 3 | **类型等价** | `tinyint(1)` 在 MySQL 是数字、在 PG 该是 boolean；`unsigned` 在 PG 不存在；`decimal` scale、`datetime(6)` 精度 | 一张显式映射表 + 建任务时校验「源类型 ∈ 目标可承载集合」 |
| 4 | **标识符与大小写** | PG 会把未加引号的标识符折叠成小写，MySQL 保留；保留字不同 | 一律显式引用（方言负责），不依赖库的默认行为 |
| 5 | **分页/边界语义** | Oracle 无 `LIMIT`；跨类型比较可能触发隐式转换 | 方言提供分页子句；主键比较保持同类型 |
| 6 | **性能** | PG 的多值 `ON CONFLICT` 与 MySQL 的多值 upsert planner 行为不同；Oracle `MERGE` 单条更合适 | 保持「切批」能力（已有 500 行上限），方言可覆盖批大小 |
| 7 | **事务与锁** | PG 默认 read committed、无 gap lock；MySQL 有间隙锁 | 位点与数据同事务这条红线不变，但要重新验一遍并发场景 |

---

## 6. 我需要你定的三件事

1. **目标库到底是哪一个？**（PostgreSQL / OceanBase Oracle 模式 / 达梦 / 人大金仓 / 都要）
   这决定方言实现与类型映射表；「都要」意味着先做抽象再批量填表。
2. **只迁数据，还是也要迁结构（自动建表）？**
   只迁数据 → 期 1~3 就够（1 周内可用）；要迁结构 → 需要期 4，且 DDL 翻译永远做不全。
3. **源端保持 MySQL 吗？**
   是 → 只做 A 档（1~2 周）。不是 → 要单独给「新 Store」立项（3~4 周，与 A 档无关）。

---

## 7. 我的建议

**先做期 1**：纯重构、零行为变化、测试可完全证明。做完之后：
- 你要接 PG，就是期 2 + 期 3，大约 4~6 天；
- 你要接 Oracle 模式，方言层已经在了，只是换一张类型映射表 + `MERGE` 语法；
- 你暂时不接，代码也更好读（`Sink` 从此只关心事务与批次）。

要不要我现在就把**期 1** 做掉？（预计改动：新增 2 个文件、重写 3 个文件的 SQL 生成部分，全部测试必须保持绿色）

---

# 附：OMS 里支持的是哪一种？

> 结论：**OMS 是 C 档（全异构）**，而且它把 C 档拆成了「四段流水线 + 两张表」，不是靠一个万能 SQL 翻译器。

## 证据 1：数据源类型有 21 种

本地 `oms-console` 代码里的 `EndpointTypeEnum` 枚举（`src/models/endpoint/constant/`）：

```
ADB · DB2_LUW · DATAHUB · DBP_OP_ROUTE · HBASE · IDB · KAFKA · LINDORM
MYSQL · OB_MYSQL · OB_MYSQL_CE · OB_ORACLE · ORACLE · POLARDB_O
POLARDB_X_1 · POLARDB_X_2 · POSTGRESQL · ROCKETMQ · SYBASE · TIDB
```

**源和目标都用这同一套枚举** —— 也就是说源可以是 Oracle，目标可以是 Kafka，这在 mini-dts 里是「源异构 + 目标异构」两件事同时发生。

## 证据 2：它用「矩阵」收口，不是任意→任意

同一份代码里每个源类型都带 `enableMigrationDest` / `enableSyncDest` 两个白名单。抓出来的实际组合：

| 源 | 数据迁移目标 | 数据同步目标 |
|---|---|---|
| MySQL | OB_MYSQL | — |
| Oracle | OB_ORACLE | — |
| TiDB / PolarDB-X | OB_MYSQL | OB_MYSQL（PolarDB-X 2.0） |
| OB_MySQL | OB_MYSQL、MYSQL、DATAHUB | OB_MYSQL、KAFKA、ADB、DATAHUB、ROCKETMQ |
| OB_Oracle | OB_ORACLE、DATAHUB | KAFKA、ROCKETMQ、DATAHUB |
| Sybase | — | ROCKETMQ |

这张矩阵很关键：**"支持异构"从来不等于"任意组合都能迁"**。产品能力其实是一张显式白名单表。

## 证据 3：变更抓取按「源类型」分实现（这才是真正的工作量）

代码里 `Binlog / OBLogProxy / LogMiner` 各自独立出现 —— 也就是说增量读取不是一个通用组件，而是**每个源类型一套**：

| 源类型 | 增量从哪来 |
|---|---|
| MySQL / TiDB / PolarDB-X | binlog |
| OceanBase | OBLogProxy / clog |
| Oracle | LogMiner（读 redo） |
| PostgreSQL | 逻辑复制槽（wal） |

这正是我方案里的 **B 档 / 期 5** —— 它不是「加一个 SQL 方言」，而是「新写一个 Store」。

## 证据 4：结构迁移是独立一段（代码里有 `结构迁移` / `SchemaMigration`）

OMS 的一条链路是四段，而不是两段：

```
结构迁移（DDL 转换 + 类型映射） → 全量迁移 → 增量同步 → 数据校验
```

结构迁移这一段就是 mini-dts 方案里的**期 4**，也是平时最容易低估的部分：DDL 转换永远有损（`unsigned`、`enum`、`json`、分区、字符集），所以 OMS 还配了**迁移评估（OMA）**先给出「不兼容项 / 改造建议」，而不是硬转。

## 对照：mini-dts 现在在哪、需要补什么

| OMS 的能力段 | mini-dts 现状 | 对应我方案里的 |
|---|---|---|
| 目标方言（写入 SQL 生成） | 只有 MySQL，且焊在 `MySqlSink` 里 | **期 1 + 期 2** |
| 类型映射 | 无（建任务时只比主键与结构指纹） | 期 2 的映射表 |
| 校验跨方言 | CRC 依赖各库自己的渲染 | **期 3**（canonical 编码） |
| 结构迁移（DDL 转换） | 无（要求目标表预先存在且结构一致） | 期 4 |
| 源异构（多源变更抓取） | 无（只有 binlog） | 期 5 / 单独立项 |
| 能力矩阵（谁能迁到谁） | 无（默认 MySQL→MySQL） | 建议**先做这张表**，比写代码更早 |

## 所以我该怎么回答你的问题

- 你说「想支持异构 SQL」→ **OMS 的答案是 C 档**：源异构 + 目标异构 + 结构迁移 + 类型映射 + 跨方言校验，五个方言点各自适配。
- 但 OMS 的落地方式很务实：**先用矩阵限定组合**（我只支持这几条链路），再一条条链路打磨。不是先造一个通用翻译器。
- 对 mini-dts 来说，可照抄的路径是：**先写能力矩阵（1 页）→ 期 1 抽方言（1~2 天）→ 挑一条链路（如 MySQL→OB_MySQL 或 MySQL→PG）打通期 2~3**。这样每次只面对一个真实组合，避免「万能异构」这个无底洞。
