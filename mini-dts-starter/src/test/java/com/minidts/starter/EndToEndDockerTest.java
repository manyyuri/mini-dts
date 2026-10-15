package com.minidts.starter;

import com.minidts.cm.FullCheckpointRepository;
import com.minidts.cm.SchemaInit;
import com.minidts.cm.TaskRepository;
import com.minidts.cm.TaskState;
import com.minidts.cm.VerifyReportRepository;
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
import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验收（Docker 门控）（需要 Docker；无 Docker 环境自动跳过）：
 * 全量 → 全量期间写入 → 增量追平 → 暂停 → 暂停期间写入 → 恢复追平 → CRC 校验。
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

    private MigrationEngineImpl engine;

    // ── helpers ───────────────────────────────────────────────────

    private void initEngine() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:cm-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaInit.init(jdbc);
        engine = new MigrationEngineImpl(new TaskRepository(jdbc),
                new FullCheckpointRepository(jdbc), new VerifyReportRepository(jdbc));
    }

    private String sourceUrl() {
        return SOURCE.getJdbcUrl();
    }

    private String targetUrl() {
        return TARGET.getJdbcUrl();
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

    // ── the test ──────────────────────────────────────────────────

    @Test
    void fullThenIncrementalThenVerify() throws Exception {
        // 源/目标建表 + 源 1000 行
        for (MySQLContainer<?> c : List.of(SOURCE, TARGET)) {
            exec(c, "CREATE TABLE shop.orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
        }
        try (Connection conn = SOURCE.createConnection(""); Statement st = conn.createStatement()) {
            for (long i = 1; i <= 1000; i++) {
                st.execute("INSERT INTO shop.orders VALUES (" + i + ", 'n" + i + "', " + (i * 0.5) + ")");
            }
        }

        initEngine();
        String taskId = engine.createTask(new com.minidts.cm.MigrationEngine.TaskCreateRequest(
                sourceUrl(), "u", "p", targetUrl(), "u", "p",
                "shop", List.of("orders"), 100, 4, 100_000));
        engine.start(taskId);

        // 全量完成 → 自动进入增量
        await("进入增量阶段").atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertEquals(TaskState.RUNNING_INCR.name(), engine.metrics(taskId).get("state")));
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

        // 暂停：暂停期间源写入，目标不动；恢复后追平
        engine.pause(taskId);
        await("暂停生效").atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(
                () -> assertEquals(TaskState.PAUSED.name(), engine.metrics(taskId).get("state")));
        exec(SOURCE, "INSERT INTO shop.orders VALUES (3001, 'during-pause', 1.00)");
        Thread.sleep(1500);
        assertEquals(0L, count(TARGET, "SELECT COUNT(*) FROM shop.orders WHERE id=3001"));

        engine.resume(taskId);
        await("恢复追平").atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(
                () -> assertEquals("during-pause", one(TARGET, "SELECT name FROM shop.orders WHERE id=3001")));

        // CRC 校验：两端应一致
        var report = engine.verify(taskId);
        assertEquals(Boolean.TRUE, report.get("allEqual"), () -> "校验差异: " + report);

        engine.finish(taskId);
        assertEquals(TaskState.FINISHED.name(), engine.metrics(taskId).get("state"));
    }
}
