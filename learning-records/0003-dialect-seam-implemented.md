# 0003 — 动手做了「方言缝合线」：我以为是语法问题，其实是幂等语义问题

- 日期：2026-10-19
- 状态：已完成（代码 + 测试 + 结论）
- 关联：`docs/异构SQL改造方案.md` §0.6、课程 `0012 改造演练`

## 做了什么（半天粒度，只动写入侧）

| 动作 | 文件 |
|---|---|
| 新增方言端口（4 个职责） | `connector/SqlDialect.java` |
| MySQL 方言（行为等价搬迁） | `connector/MySqlDialect.java` |
| H2 方言（单测用，`MERGE INTO`） | `connector/H2Dialect.java` |
| PostgreSQL 方言（**只生成 SQL，不连库**） | `connector/PostgresDialect.java` |
| 按产品名探测方言 | `connector/SqlDialectFactory.java` |
| `MySqlSink` 删掉全部 SQL 拼装，只留事务/批次/位点/死信 | `connector/MySqlSink.java` |
| 自用表 DDL/upsert 也走方言（不再判断"是不是 MySQL"） | `connector/TargetSchemaInit.java`、`TargetAdminImpl.java` |
| 一致性测试：两方言生成的 SQL **结构对齐** | `connector/DialectConformanceTest.java` |

结果：`mvn test` → 77 个用例、72 通过、0 失败（5 个 Docker 门控跳过）。
`MySqlSink` 里 `ON DUPLICATE` / `INSERT INTO` / `DELETE FROM` / `quote()` **全部归零**。

## 我原来以为 → 实际是

| 我原来以为 | 实际是 |
|---|---|
| 方言差异 = 引号与关键字不一样，写个`if` 就能切 | 差异在**幂等的可得性**：PG 必须显式给冲突目标（`ON CONFLICT (pk)`），Oracle 干脆没有 upsert |
| 端口可以只传「表 + 列 + 行数」 | 端口**必须带主键列**，否则 PG 无法生成合法语句 —— 这是抽象漏掉的第一件事，被测试当场抓住 |
| 差异在 SQL 层，应该"翻译" | 差异会**溢出到别的层**：PG 要冲突目标 → 建任务时的主键校验从"可选"变"必须"；Oracle 无 upsert → 「重放安全」这块地基要换实现 |
| 换目标库要改写入端 | 换目标库只加一个类；`Sink` 一行不用改（这次 diff 证明了） |

## 三条可复用的结论（以后评审 OMS 相关需求时直接用）

1. **方言边界 = 幂等实现的边界。** 判断一个目标库"能不能接"，先问它的 upsert 语义（是否原子、是否需要显式冲突键、是否有并发竞态），而不是问它 SQL 像不像 MySQL。
2. **端口签名暴露设计漏洞。** 只要签名里没有主键列，就说明这个抽象还没想清楚 —— 因为有一类目标库根本无法在缺主键时表达幂等。
3. **差异溢出到哪里，工作量就在哪里。** 冲突目标一进来，建任务校验、幂等契约、能力矩阵都会跟着变；所以 OMS 才需要"按目标类型分实现 + 能力矩阵"，而不是一个万能翻译器。

## 一个还没解的问题（留给下次）

Oracle 目标：`MERGE INTO` 有并发竞态（并发插入会抛唯一键冲突），业界做法是「MERGE + 捕获唯一键冲突重试」。
这意味着**幂等从"一条语句的属性"变成"一段重试策略"** —— 那么 `Sink` 的失败处理（当前是逐行重试一次 + 死信）该怎么改造？
这个问题问下去，就能摸到 OMS 里 Connector 的失败处理为什么要做成那样。
