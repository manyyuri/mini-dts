# 0004 — 期 2 的「无环境」部分 + 期 3 的 core：类型映射与跨方言 canonical 编码

- 日期：2026-10-19
- 状态：完成（不依赖外部数据库；期 2 的落库验证仍缺环境）
- 关联：`docs/异构SQL改造方案.md` §0.7、`learning-records/0003`

## 做了什么

**（a）类型映射与迁移评估**（期 2 无环境部分）

| 文件 | 作用 |
|---|---|
| `common/TypeFamily.java` | 类型等价类（INTEGER / DECIMAL / STRING / LONG_TEXT / BOOLEAN / BINARY / DATETIME / DATE / TIME / JSON / ENUM_SET） |
| `common/ColumnType.java` | 一列的「类型事实」：原始名 + 等价类 + 是否 unsigned（`tinyint(1)` → BOOLEAN） |
| `common/ColumnTypes.java` | 从 `information_schema` 读列类型 |
| `connector/TypeMapper.java` | 类型映射端口（与 `SqlDialect` 分开：一个管"能不能搬"，一个管"语句怎么写"） |
| `connector/MySqlTypeMapper.java` / `PostgresTypeMapper.java` | 映射 + **不兼容项说明** |
| `connector/TypeCompatibility.java` | 纯函数：产出「表(列)：原因」清单 + 目标类型建议 |
| `MigrationEngineImpl.createTask` | 加一道闸门：类型不兼容**在建任务时**就拒绝并给出改造建议 |

**（c）跨方言 canonical 编码**（期 3 的核心）

- `Values.normalize`：新增 `OffsetDateTime / ZonedDateTime / Instant`（按 JVM 时区折算成本地时间）
- `RowCodec.cell`：小数**去尾零**后 `toPlainString`（`1.00` 与 `1.0` 同值）；`cell` 内部再归一一次（防外部直调漏归一）
- `SchemaFingerprint`：从「比原始类型名」改成**按等价类**算指纹
- 新增测试：`ColumnTypeTest`(5) / `TypeMappingTest`(8) / `RowCodecCanonicalTest`(8) / 指纹跨方言(2)

`mvn test`：**100 用例 / 95 通过 / 0 失败**（5 个 Docker 门控跳过）。

## 三个新认知

1. **类型映射表的产出不是"目标类型"，是"人话原因"。**
   `unsigned`、MySQL `timestamp` 的会话时区语义、PG `jsonb` 重排键——这些都能"映射成功"，
   但都会在运行期变成数据错误或 CRC 假差异。所以评估报告必须写成"不拦你会怎样"，
   这和课程 `0015 六道闸门` 是同一个思路。

2. **`jsonb` 那条最反直觉**：写入完全正确，但 CRC 永远不等（键序、数字规范化）。
   这说明「校验口径」必须和「存储类型」解耦——校验应该比**规范化后的值**，
   而不是比库里的字节。这正是 `RowCodec` 存在的理由，也是跨方言场景下它必须升级的理由。

3. **指纹必须按等价类算。** 原来比 `DATA_TYPE` 原值，`BIGINT` 与 `int8` 会被判成结构不一致；
   跨方言下这等于"任务永远建不起来"。改成按族比较后，`INTEGER` 与 `STRING` 仍然能报出真差异。

## 还没做的（诚实清单）

- 期 2 落库验证：需要真实 PostgreSQL（Docker Hub 不可达、H2 的 PG 模式不支持 `ON CONFLICT ... DO UPDATE`）
- 期 3 剩余：`VerifyService` 按「表结构类型」选择规范化策略（如 JSON 列做键排序后再比）——当前只是编码层统一
- 期 4：DDL 翻译（反向生成 CREATE TABLE、跨方言 ADD COLUMN）
