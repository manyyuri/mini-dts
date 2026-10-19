package com.minidts.starter;

import com.minidts.cm.MigrationEngine.TaskCreateRequest;
import com.minidts.cm.SchemaInit;
import com.minidts.cm.TaskLeaseManager;
import com.minidts.cm.TaskRepository;
import com.minidts.cm.TaskState;
import com.minidts.cm.VerifyReportRepository;
import com.minidts.common.GlobalRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验收（Docker 门控）：真实 MySQL 双实例 + 真实 binlog。
 *
 * 覆盖：全量 → 增量 → 暂停/恢复 → 校验；以及本次修复的三类缺陷：
 * - DDL：白名单 ADD COLUMN 自动落地目标端并继续同步；破坏性 DDL 停任务 + 留台账
 * - 多节点：租约排他（第二个节点抢不到同一任务）
 * - 崩溃接管：DB 里残留在跑状态、运行时已消失，新节点 reattach 后从目标库位点续跑
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndDockerTest {

    @Container
    static final MySQLContainer<?> SOURCE = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("shop").withUsername("u").withPassword("p")
            .withCommand("--server-id=1", "--log-bin=binlog", "--binlog-format=ROW",
                    "--binlog_row_image=FULL", "--gtid-mode=ON", "--enforce-gtid-consistency=ON");

    @Container
    static final MySQLContainer<?> TARGET = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("shop").withUsername("u").withPassword("p")
            .withCommand("--server-id=2", "--log-bin=binlog", "--binlog-format=ROW",
                    "--binlog_row_image=FULL", "--gtid-mode=ON", "--enforce-gtid-consistency=ON");

    private JdbcTemplate jdbc;
    private MigrationEngineImpl engine;

    // ── helpers ───────────────────────────────────────────────────

    /** 一个"节点"：共享元数据库 + 独立 nodeId（多节点部署的最小复刻）。 */
    private MigrationEngineImpl newNode(String nodeId) {
        if (jdbc == null) {
            SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                    "jdbc:h2:mem:cm-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(ds);
            SchemaInit.init(jdbc);
        }
        EngineProperties props = new EngineProperties(nodeId, 8, 200_000L,
                Duration.ofSeconds(30), 10_000L, false, 10,
                Duration.ofMillis(200), Duration.ofSeconds(2), 20_000);
        return new MigrationEngineImpl(new TaskRepository(jdbc), new VerifyReportRepository(jdbc),
                new TaskLeaseManager(jdbc, props.nodeId(), props.leaseTtl()),
                new GlobalRateLimiter(props.globalRowsPerSecond()), props);
    }

    private void createTable(String table) throws Exception {
        for (MySQLContainer<?> c : List.of(SOURCE, TARGET)) {
            exec(c, "CREATE TABLE shop." + table + " (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
        }
    }

    private TaskCreateRequest request(String table) {
        return new TaskCreateRequest(SOURCE.getJdbcUrl(), "u", "p", TARGET.getJdbcUrl(), "u", "p",
                "shop", List.of(table), 100, 4, 100_000);
    }

    private void exec(MySQLContainer<?> c, String sql) throws Exception {
        try (Connection conn = c.createConnection(""); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private long count(MySQLContainer<?> c, String sql) throws Exception {
        try (Connection conn = c.createConnection(""); Statement st = conn.createStatement()) {
            var rs = st.executeQuery(sql);
            rs.next();
            return rs.getLong(1);
        }
    }

    private String one(MySQLContainer<?> c, String sql) throws Exception {
        try (Connection conn = c.createConnection(""); Statement st = conn.createStatement()) {
            var rs = st.executeQuery(sql);
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void awaitIncr(String taskId) {
        await("进入增量阶段").atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertEquals(TaskState.RUNNING_INCR.name(), engine.metrics(taskId).get("state")));
    }

    // ── 主链路 ────────────────────────────────────────────────────

    @Test
    void fullThenIncrementalThenVerify() throws Exception {
        createTable("orders");
        try (Connection conn = SOURCE.createConnection(""); Statement st = conn.createStatement()) {
            for (long i = 1; i <= 1000; i++) {
                st.execute("INSERT INTO shop.orders VALUES (" + i + ", 'n" + i + "', " + (i * 0.5) + ")");
            }
        }
        engine = newNode("node-main");
        String taskId = engine.createTask(request("orders"));
        engine.start(taskId);
        awaitIncr(taskId);
        assertEquals(1000L, count(TARGET, "SELECT COUNT(*) FROM shop.orders"));

        // 增量：INSERT / UPDATE / DELETE / PK 变更
        exec(SOURCE, "INSERT INTO shop.orders VALUES (1001, 'new', 9.99)");
        exec(SOURCE, "UPDATE shop.orders SET name='upd' WHERE id=1");
        exec(SOURCE, "DELETE FROM shop.orders WHERE id=2");
        exec(SOURCE, "UPDATE shop.orders SET id=2001 WHERE id=3");   // PK 变更 = DELETE 3 + UPSERT 2001

        await("增量追平").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertEquals(1000L, count(TARGET, "SELECT COUNT(*) FROM shop.orders"));
            assertEquals("upd", one(TARGET, "SELECT name FROM shop.orders WHERE id=1"));
            assertEquals(0L, count(TARGET, "SELECT COUNT(*) FROM shop.orders WHERE id=2"));
            assertEquals(0L, count(TARGET, "SELECT COUNT(*) FROM shop.orders WHERE id=3"));
            assertEquals("n3", one(TARGET, "SELECT name FROM shop.orders WHERE id=2001"));
        });

        // 暂停 → 源继续写 → 恢复追平
        engine.pause(taskId);
        await("暂停生效").atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(
                () -> assertEquals(TaskState.PAUSED.name(), engine.metrics(taskId).get("state")));
        exec(SOURCE, "INSERT INTO shop.orders VALUES (3001, 'during-pause', 1.00)");
        Thread.sleep(1500);
        assertEquals(0L, count(TARGET, "SELECT COUNT(*) FROM shop.orders WHERE id=3001"));

        engine.resume(taskId);
        await("恢复追平").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertEquals("during-pause", one(TARGET, "SELECT name FROM shop.orders WHERE id=3001")));

        var report = engine.verify(taskId);
        assertEquals(Boolean.TRUE, report.get("allEqual"), () -> "校验差异: " + report);

        engine.finish(taskId);
        assertEquals(TaskState.FINISHED.name(), engine.metrics(taskId).get("state"));
    }

    // ── DDL ───────────────────────────────────────────────────────

    @Test
    void addColumnDdlIsAppliedToTargetAndSyncContinues() throws Exception {
        createTable("orders_ddl");
        exec(SOURCE, "INSERT INTO shop.orders_ddl VALUES (1, 'a', 1.00)");
        engine = newNode("node-ddl");
        String taskId = engine.createTask(request("orders_ddl"));
        engine.start(taskId);
        awaitIncr(taskId);

        // 白名单 DDL：目标端应被自动加列，且同步不中断
        exec(SOURCE, "ALTER TABLE shop.orders_ddl ADD COLUMN memo VARCHAR(32)");
        await("目标端自动加列").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).untilAsserted(
                () -> assertEquals(1L, count(TARGET,
                        "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema='shop' AND table_name='orders_ddl' AND column_name='memo'")));

        exec(SOURCE, "INSERT INTO shop.orders_ddl VALUES (2, 'b', 2.00, 'hello')");
        await("加列后的数据继续同步").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertEquals("hello", one(TARGET, "SELECT memo FROM shop.orders_ddl WHERE id=2")));
        assertEquals(TaskState.RUNNING_INCR.name(), engine.metrics(taskId).get("state"), "白名单 DDL 不该停任务");
    }

    @Test
    void destructiveDdlPausesTaskAndJournals() throws Exception {
        createTable("orders_ddl2");
        exec(SOURCE, "INSERT INTO shop.orders_ddl2 VALUES (1, 'a', 1.00)");
        engine = newNode("node-ddl2");
        String taskId = engine.createTask(request("orders_ddl2"));
        engine.start(taskId);
        awaitIncr(taskId);

        exec(SOURCE, "ALTER TABLE shop.orders_ddl2 MODIFY COLUMN name VARCHAR(128)");

        await("破坏性 DDL → 任务暂停").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).untilAsserted(
                () -> assertEquals(TaskState.PAUSED.name(), engine.metrics(taskId).get("state")));
        assertEquals(1L, count(TARGET, "SELECT COUNT(*) FROM dts_ddl_event WHERE task_id='" + taskId + "'"),
                "DDL 必须留台账（老实现是静默丢弃）");
        assertEquals(0L, count(TARGET, "SELECT COUNT(*) FROM dts_ddl_event WHERE task_id='" + taskId + "' AND applied=1"));
    }

    // ── 多节点 / 恢复 ─────────────────────────────────────────────

    @Test
    void otherNodeCannotRunTheSameTask() throws Exception {
        createTable("orders_lease");
        exec(SOURCE, "INSERT INTO shop.orders_lease VALUES (1, 'a', 1.00)");
        engine = newNode("node-a");
        String taskId = engine.createTask(request("orders_lease"));
        engine.start(taskId);
        awaitIncr(taskId);

        MigrationEngineImpl nodeB = newNode("node-b");
        assertThrows(Exception.class, () -> nodeB.start(taskId), "运行中的任务不能再被 start");
        assertFalse(nodeB.reattach(taskId), "租约在 node-a，node-b 不许接管");
    }

    @Test
    void crashedTaskIsReattachedFromTargetCheckpoint() throws Exception {
        createTable("orders_rec");
        exec(SOURCE, "INSERT INTO shop.orders_rec VALUES (1, 'a', 1.00)");
        engine = newNode("node-crash");
        String taskId = engine.createTask(request("orders_rec"));
        engine.start(taskId);
        awaitIncr(taskId);

        // 模拟进程崩溃：运行时消失，但 DB 里状态仍是 RUNNING_INCR
        engine.pause(taskId);
        jdbc.update("UPDATE dts_task SET state = 'RUNNING_INCR', full_done = TRUE WHERE id = ?", taskId);

        MigrationEngineImpl newNode = newNode("node-takeover");
        exec(SOURCE, "INSERT INTO shop.orders_rec VALUES (2, 'after-crash', 2.00)");
        assertTrue(newNode.reattach(taskId), "残留运行态 + 空闲租约 → 应被接管");

        await("接管后继续同步").atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertEquals("after-crash", one(TARGET, "SELECT name FROM shop.orders_rec WHERE id=2")));
        assertEquals(TaskState.RUNNING_INCR.name(), newNode.metrics(taskId).get("state"));
    }
}
