# mini-dts 四项质疑核查：单源假设 / 无 DDL / 无 HA / 无分布式编排

> 核查范围：`master` @ `1020d59`，逐文件读代码（3524 行 Java）+ README「已知局限」+ 单测/E2E。
> 结论：**四个问题全部成立**，但性质不同 —— 2 项是"已声明的 demo 局限"，2 项是"未声明且含真 bug"。

## 0. 结论速览

| 质疑 | 是否成立 | 性质 | 最重后果 |
|---|---|---|---|
| 单源假设 | ✅ 成立 | 部分已声明（GTID 语义），**另有两处未声明的真缺陷** | 同实例同名表串库写入、目标库静默写错 schema |
| 无 DDL 处理 | ✅ 成立 | README 已声明「DDL 变更不处理」 | 加列即写入错位 → 死信 + 增量线程静默死 |
| 无 HA | ✅ 成立 | **未声明，且存在 P0「假活」bug** | 增量线程死了状态还是 RUNNING_INCR，无人知晓 |
| 无多组件分布式编排 | ✅ 成立 | 架构级，未声明 | 编排全在单 JVM 内存，组件不可跨机、不可伸缩、不可接管 |

---

## 1. 单源假设 —— 成立，而且不止 README 说的那一层

### 1.1 已声明的那层（GtidState）

`mini-dts-common/.../GtidState.java` 按 sid 记 `max(gno)`，README 明说「多源写同一实例需完整并集语义」。
这是**可接受的 demo 简化**：单主库 GTID 单调递增，max-per-sid 即正确。

### 1.2 未声明的缺陷 A：TABLE_MAP 只记表名、不记 database → 跨库串写 ⚠️

```java
// mini-dts-store/.../BinlogStoreService.java:139-140
TableMapEventData tm = (TableMapEventData) data;
tableById.put(tm.getTableId(), tm.getTable());      // ← tm.getDatabase() 被丢弃
// :177
if (name == null || !inScope(name)) { ... }         // ← inScope 只比表名
// :256
return tables.isEmpty() || tables.contains(table);  // ← tables 空 = 整库 = 所有库同名表都命中
```

**后果**：同一个 MySQL 实例里 `db_a.orders` 和 `db_b.orders` 同时存在时，两份 binlog 行都会命中 `inScope("orders")`，双双回放进**同一个目标库**（目标 schema 由 JDBC URL 决定），数据互相覆盖。默认「整库同步」（`tables` 为空）时，等于把实例下所有 schema 的同名表全搅在一起。

### 1.3 未声明的缺陷 B：源端按 schema 限定，目标端靠 JDBC URL 推断

- 源端读数据：`JdbcPkBoundaryScanner.qualify(schema, table)` → 显式 `schema.table`
- 目标端写数据：`MySqlSink.java:104/111/133` → `quote(first.table())`，**无 schema 限定**，落到连接 URL 的默认库

**后果**：当 `sourceUrl` 与请求体里的 `schema`、`targetUrl` 的库名三者不一致时，任务能建成功、能跑，只是默默写错库 —— 没有任何一致性校验。

### 1.4 其余单源硬编码

| 位置 | 内容 |
|---|---|
| `MigrationEngineImpl.createTask` | 单个 `sourceUrl` / `targetUrl` / `schema`，无多源合并、无分库分表聚合、无反向（双活） |
| `BinlogStoreService.buildClient` | `setServerId(now%100000+1)` —— 与真实从库或其它任务撞 id 的概率非零，撞了源库会踢掉另一路订阅 |
| `MigrationEngineImpl` | 一致性位点 `@@GLOBAL.gtid_executed` 每任务只读一次，无法处理源端主从切换后的位点漂移 |

---

## 2. 无 DDL 处理 —— 成立，但后果比 README 写的"报错"更严重

### 2.1 binlog 里 DDL 被整类丢弃

```java
// BinlogStoreService.java:168
// QUERY / ROTATE / FORMAT_DESCRIPTION 等：非数据事件，忽略
```

MySQL 的 `ALTER / CREATE / DROP / TRUNCATE / RENAME` 都以 **QUERY 事件**进 binlog。这一行忽略 = 所有 DDL 全部丢失，且**不留死信、不留日志、不进指标**。

### 2.2 列元数据是启动时快照

`preloadTableMetadata()` 一次性构建不可变 `columnsByTable` / `pkColumnsByTable`，运行期永不刷新（`docs/项目讲解.md:1712` 也承认）。

### 2.3 目标端业务表 DDL 完全靠人工

`TargetSchemaInit.ensure()` 只建 `dts_incr_checkpoint` + `dts_dead_letter` 两张自用表；**没有 `SHOW CREATE TABLE` 复制、没有字段/索引/字符集比对**。E2E 测试也是自己在源和目标各跑一遍 `CREATE TABLE shop.orders`（`EndToEndDockerTest.java:93`）——这正说明建表不在产品能力内。

### 2.4 后果升级链（比"报错"难看）

| 源端 DDL | 实际发生什么 |
|---|---|
| `ALTER TABLE ADD COLUMN` | 行镜像列数 > 缓存列数 → `RowRecord` 列名与值长度错位 → INSERT 占位符与值个数不符 → 写入失败 → 走"逐行重试 + 落死信" → 抛错 → **增量线程静默死** |
| `CREATE TABLE` 新表 | `forTarget` 抛 `表 X 缺少列元数据（任务运行中新增表？）` → 同样静默死 |
| `DROP / TRUNCATE` | 不传播 → 目标端残留"源已没有"的数据，校验才暴露 |
| `RENAME` | 旧映射继续按旧表名写 → 目标端错表 |

### 2.5 缺失清单

DDL 解析与白名单、schema 版本/列指纹校验、DDL 死信与人工介入通道、建表语句（含 charset/collation/index）复制、DDL 期间自动暂停-重载元数据。

---

## 3. 无 HA —— 成立，且这里有本次核查最该修的一个 bug

### 3.1 P0：增量线程"静默死亡"，状态却是 RUNNING_INCR ⚠️⚠️

```java
// BinlogStoreService.java:124-131
public void onEvent(Event event) {
    try { handle(event); }
    catch (Exception e) { failure = e; stop(); }   // ← 只 stop，不通知
}
// :95  start()
client.connect();   // 阻塞；stop() 或异常时返回
} catch (Exception e) {                            // ← onEvent 里 stop() 走的是"正常返回"，
    failure = e; running.set(false);               //    不抛异常 → 永远进不来这个 catch
    notifier.onError(taskId, "增量同步失败/退出: " + e.getMessage());
}
```

`stop()` 让 `connect()` **正常返回**，`start()` 顺着 try 正常结束，`notifier.onError` 一次都不调。
结果：任务状态停在 `RUNNING_INCR`，`incrTxApplied` 冻结，`/metrics` 一切正常，**实际已经不同步了**。

### 3.2 无重连 / 无退避

`BinaryLogClient` 断开即返回，外面没有 retry 循环。源库重启、网络抖动、binlog 被 purge，都直接终局。

### 3.3 无进程重启恢复

全仓 grep 无 `CommandLineRunner / ApplicationRunner / @PostConstruct / @Scheduled`。
进程重启后：H2 里任务仍是 `RUNNING_FULL/RUNNING_INCR`，`runtimes` 内存表已空，**没有任何启动扫描把任务重新拉起**。

### 3.4 恢复路径还有死角

`resume(taskId)` → `resumeTo(fullDone=true)` = `RUNNING_INCR` → `transition(RUNNING_INCR, RUNNING_INCR)` → 抛「状态自迁移无意义」。
即：崩溃后残留 `RUNNING_INCR` 的任务**不能直接 resume**，必须先 `pause` 再 `resume` —— 无人知道这个隐藏步骤。

### 3.5 断点存储分裂，天然排他地禁止了接管

| 断点 | 存储位置 | 能否共享 |
|---|---|---|
| 全量 chunk 断点 | CM 侧 H2 文件（`dts_full_checkpoint`，`jdbc:h2:file:./data/...AUTO_SERVER`） | ❌ 单机磁盘 |
| 增量位点 | 目标库 `dts_incr_checkpoint`（与业务数据同事务，设计正确） | ✅ |

断点两处异地：就算把进程搬到另一台机器，全量断点也没了。

### 3.6 没有双跑保护

无分布式锁 / 租约 / leader 选举。两个实例同时 `start` 同一任务 = 两个 binlog 客户端 + 两边互相覆盖 `dts_incr_checkpoint`，目标端虽有幂等 upsert 兜底，但位点可能**倒退**（旧事务后提交）→ 重复回放窗口放大。

### 3.7 可观测性缺口

指标只存在进程内 `AtomicLong`（`rowsCopied / chunksDone / txApplied`），无心跳上报、无延迟（lag）计算、无告警钩子。重启即归零。

### 3.8 顺带暴露的容量风险（与 HA 同源）

`txBuffer` 无上限（`BinlogStoreService.java:71`），大事务整包进内存；`MySqlSink.applyUpsert` 把整个 run 拼成**单条多值 INSERT**，十万行级事务会撞 `max_allowed_packet` → 死信 → 3.1 的静默死。

---

## 4. 无多组件分布式编排 —— 成立（架构级）

### 4.1 编排 = 单 JVM 内存

```java
// MigrationEngineImpl
private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();   // 进程内对象表
public synchronized void start(...) { ... }                                // 单机 synchronized 当锁
new Thread(() -> { ... }, "full-" + t.id());                               // 裸线程，无池无托管
```

"控制面"与"执行面"在同一个进程、同一份内存里，`Runtime` 持有的是 `FullTransferService` / `BinlogStoreService` **Java 对象引用** —— 这些东西天生无法序列化、无法下发到别的机器。

### 4.2 组件间是 Java 接口直连，不是 RPC/MQ

`Sink`、`FullCheckpointStore`、`TaskStateNotifier`、`VerifyReportStore` 全是同进程接口。模块化只是**编译期分包**，运行时并不解耦：Store 不能独立扩容，Connector 不能横向加消费组，Verifier 不能单独调资源。

### 4.3 多表全量是"单机串行"，不是分片下发

```java
first.run();                                              // 表 0
for (int i = 1; i < totalTables; i++) { ... svc.run(); }   // 表 1..N 一张一张来
```

只有**表内**有 `parallelism` 个 worker；跨表、跨任务、跨节点没有任何调度。

### 4.4 编排要素全缺

worker 注册/心跳、任务切片下发、租约与超时回收、故障转移、节点容量感知、全局限流（现在 `TokenBucket` 是"每表实例一个"，多任务叠加不限总量）、任务优先级/队列。

### 4.5 状态推进靠内存回调

`TaskStateNotifier` 是内存回调，状态落库是"回调里顺手 update 一下"。没有事件表/幂等投递/重放，所以**状态机本身也不可跨进程恢复**（与 3.3 互为因果）。

### 4.6 对照 OMS 真实形态

| 组件 | OMS 真实形态 | mini-dts 现状 |
|---|---|---|
| CM | 独立服务 + 共享元数据库 + 多实例 | 与执行同进程，元数据本地 H2 文件 |
| Store | 独立拉取进程，可多实例、可共享 | 进程内线程，1 任务 1 客户端 |
| Connector | 独立写入进程，可水平扩 | 进程内对象 |
| Full / Verifier | 独立 worker 池 | 裸 `new Thread` + 固定线程池 |
| 编排 | 调度器 + 心跳 + 租约 | `runtimes` HashMap |

---

## 5. 修复优先级建议

**P0 — 是 bug 不是缺功能，先修（改动小、收益最大）**

1. `onEvent` 失败必须显式 `notifier.onError(...)`，让 3.1 的静默死变成可见的 `ERROR` 状态（1 行级改动）
2. `TABLE_MAP` 带上 `tm.getDatabase()`，`inScope` 改成 `(database, table)` 双键匹配（防串库）
3. 建任务时校验 `schema` 与 `targetUrl` 的库名一致，或 Sink 统一加 schema 限定（防写错库）
4. `resume()` 允许 `RUNNING_* → RUNNING_*` 的"重挂"语义，或在启动扫描时自动降级为 `ERROR/PAUSED`

**P1 — 让它配得上"断点续传"这四个字**

5. 增量重连 + 指数退避，断开不终局
6. 启动扫描：`RUNNING_*` 状态按 `fullDone` 自动重挂（配合 5.5 的断点迁移）
7. 全量断点从 H2 迁到目标库（与增量断点同址），为跨节点接管铺路

**P2 — DDL**

8. `QUERY` 事件里识别 DDL：先落 `dts_ddl_event` 表 + 指标 + 告警，再按白名单（如 `ADD COLUMN`）尝试执行并刷新元数据缓存，白名单外则暂停任务等人工
9. 建任务时落一份「列指纹」，运行期定期比对，提前发现 schema 漂移

**P3 — 分布式**

10. CM 元数据迁到共享库（MySQL），执行面拆成独立 worker 进程，加租约表 + 心跳
11. 组件间换成 HTTP/gRPC 或 MQ，先做"Store 与 Connector 跨进程"，再做"多 worker 分片"
12. 全局限流器（Redis/DB 令牌桶）与任务队列

---

## 6. 一句话总结

> 四项质疑全部成立。其中「单源假设」「DDL 不处理」是**自觉地写在 README 里的 demo 边界**，可以接受；
> 但「无 HA」不是简单的缺功能 —— 它和「增量线程静默死亡」这个真 bug 叠在一起，会让系统**在看起来健康的状态下停止同步**；
> 「无分布式编排」则是当前模块化（common/connector/full/store/verifier/cm）的**真实天花板**：分包做得干净，但运行时从未离开过同一个 JVM。
