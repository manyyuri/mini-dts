package com.minidts.connector;

import com.minidts.common.DbEndpoint;
import com.minidts.common.DdlEvent;
import com.minidts.common.FullCheckpointStore;
import com.minidts.common.OpType;
import com.minidts.common.RowRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标库自用表：全量断点、DDL 台账、结构指纹、库名限定。
 *
 * 这三件事对应被修掉的三个缺陷：
 * - 断点从控制面本地库挪到目标库 → 换节点接管能接着跑
 * - DDL 有台账 → 不再「静默丢弃」
 * - 指纹可登记/可读取 → 结构漂移能在写入前被发现
 */
class TargetStoreH2Test {

    private String url;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:targetstore-" + System.nanoTime()
              + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        conn = DriverManager.getConnection(url, "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS shop");
            st.execute("CREATE TABLE shop.orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE shop.items (id BIGINT PRIMARY KEY)");
        }
        TargetSchemaInit.ensure(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void fullCheckpointSurvivesNewStoreInstance() {
        FullCheckpointStore first = new TargetFullCheckpointStore(conn);
        first.markChunkDone("t1", "100");
        first.markChunkDone("t1", "200");
        first.markChunkDone("t1", "100");        // 重做同一分片不能炸

        FullCheckpointStore second = new TargetFullCheckpointStore(conn);   // 模拟换节点接管
        assertEquals(Set.of("100", "200"), second.completedUpperPks("t1"));
        assertEquals(Set.of(), second.completedUpperPks("other-task"), "断点必须按任务隔离");
    }

    @Test
    void journalWritesRowWithStatementAndReason() throws Exception {
        TargetAdminImpl admin = admin();
        try {
            admin.journal(new DdlEvent("t9", "OTHER_DDL", "shop", "orders",
                    "DROP TABLE orders", "gtid:9", false, "非白名单 DDL"));
            try (ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT task_id, kind, table_name, applied, reason, statement FROM dts_ddl_event")) {
                assertTrue(rs.next(), "DDL 必须留下台账");
                assertEquals("t9", rs.getString(1));
                assertEquals("OTHER_DDL", rs.getString(2));
                assertEquals("orders", rs.getString(3));
                assertFalse(rs.getBoolean(4));
                assertTrue(rs.getString(5).contains("非白名单"));
                assertEquals("DROP TABLE orders", rs.getString(6));
            }
        } finally {
            admin.close();
        }
    }

    @Test
    void whitelistedDdlAppliedToTarget() throws Exception {
        TargetAdminImpl admin = admin();
        try {
            admin.applyDdl("shop", "ALTER TABLE orders ADD COLUMN memo VARCHAR(64)");
            assertEquals(3, columnsOf("shop", "orders"), "ADD COLUMN 应真的落到目标表");
        } finally {
            admin.close();
        }
    }

    @Test
    void fingerprintsRoundTrip() {
        TargetAdminImpl admin = admin();
        try {
            admin.putFingerprints("t1", Map.of("orders", "abc123", "items", "def456"));
            assertEquals(Map.of("orders", "abc123", "items", "def456"), admin.fingerprints("t1"));
            assertEquals(Map.of(), admin.fingerprints("t2"), "指纹必须按任务隔离");

            admin.putFingerprints("t1", Map.of("orders", "updated"));
            assertEquals("updated", admin.fingerprints("t1").get("orders"), "重复登记应覆盖");
            assertEquals("def456", admin.fingerprints("t1").get("items"), "未提及的表不受影响");
        } finally {
            admin.close();
        }
    }

    @Test
    void sinkWithTargetSchemaWritesOnlyThere() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE public.orders (id BIGINT PRIMARY KEY, name VARCHAR(64))");
        }
        MySqlSink sink = new MySqlSink(conn, "shop", "t1", Map.of("orders", List.of("id")));
        sink.writeBatch(List.of(new RowRecord("shop", "orders", List.of("id", "name"),
                Arrays.asList(1L, "a"), OpType.UPSERT, "tx")), null);

        assertEquals(1, count("SELECT COUNT(*) FROM shop.orders"));
        assertEquals(0, count("SELECT COUNT(*) FROM public.orders"), "库名限定后不该写进别的库");
    }

    private TargetAdminImpl admin() {
        return TargetAdminImpl.open(new DbEndpoint(url, "sa", ""));
    }

    private int columnsOf(String schema, String table) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = '" + schema
                + "' AND TABLE_NAME = '" + table + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private long count(String sql) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
