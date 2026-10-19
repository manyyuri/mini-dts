# RESOURCES — mini-dts 学习的可信来源

## 一手：项目本身

| 资源 | 用途 | 备注 |
|---|---|---|
| `README.md`（红线 → 代码 / 已知局限） | 设计契约与能力边界 | 最权威的一页，改了代码就要改它 |
| `docs/项目讲解.md` | 逐行注释版细节 | 适合查，不适合建立整体认识 |
| `lessons/0001-mini-dts-panorama.md` | 本课程第一课（先图后字） | 语雀上有同版成品 |
| `code-review-四项质疑核查.md` | 四类缺陷（单源污染/DDL/HA/编排）的取证过程 | 理解「为什么要有这些机制」的反面教材 |

## 一手：外部权威文档（概念澄清用）

- MySQL 二进制日志总览：https://dev.mysql.com/doc/refman/8.0/en/binary-log.html
- binlog 格式（ROW / STATEMENT / MIXED）：https://dev.mysql.com/doc/refman/8.0/en/binary-log-formats.html
- GTID 概念与 `gtid_executed`：https://dev.mysql.com/doc/refman/8.0/en/replication-gtids.html
- `INSERT … ON DUPLICATE KEY UPDATE`（UPSERT 语义）：https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html
- `binlog_row_image=FULL`（前镜像/后镜像）：https://dev.mysql.com/doc/refman/8.0/en/replication-options-binary-log.html

## 社区（求 wisdom 用）

- MySQL 官方论坛 Replication 版块：https://forums.mysql.com/
- Stack Overflow `mysql-binlog` / `database-replication` 标签：https://stackoverflow.com/questions/tagged/replication
- 公司内：OMS / 数据迁移相关群与 DBA 同学（实践口径比文档更准，尤其是线上事故经验）

## 阅读顺序建议

1. 本课（全景 + 一条数据的旅程）
2. `README.md` 的红线表
3. MySQL binlog 格式 + GTID 两页官方文档
4. 按需钻代码（见第 11 节索引表）
