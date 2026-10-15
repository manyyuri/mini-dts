package com.minidts.connector;

import com.minidts.common.OpType;
import com.minidts.common.RowRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** H2(MySQL 模式) 上的幂等 / 顺序 / 位点同事务 / 死信用例。 */
class MySqlSinkH2Test {

    Connection target;

    @BeforeEach
    void setUp() throws Exception {
        target = DriverManager.getConnection(
                "jdbc:h2:mem:sink;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE", "sa", "");
        try (Statement st = target.createStatement()) {
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2), created_at TIMESTAMP)");
            st.execute("CREATE TABLE items (id BIGINT PRIMARY KEY, label VARCHAR(64))");
        }
        TargetSchemaInit.ensure(target);
    }

    @AfterEach
    void tearDown() throws SQLException {
        target.close();
    }

    private RowRecord row(long id, String name, String amount, LocalDateTime ts) {
        return new RowRecord(null, "orders",
                List.of("id", "name", "amount", "created_at"),
                Arrays.asList(id, name, new BigDecimal(amount), ts),
                OpType.UPSERT, "chunk-1");
    }

    @Test
    void upsertIsIdempotent() throws Exception {
        MySqlSink sink = new MySqlSink(target, "t1", Map.of("orders", List.of("id")));
        RowRecord r = row(1, "a", "10.50", LocalDateTime.of(2026, 1, 1, 0, 0));
        sink.writeBatch(List.of(r), null);
        sink.writeBatch(List.of(r), null);   // 重放同一条
        RowRecord updated = row(1, "a2", "11.00", LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000));
        sink.writeBatch(List.of(updated), null);
        try (ResultSet rs = target.createStatement().executeQuery(
                "SELECT name, amount, created_at FROM orders WHERE id=1")) {
            assertTrue(rs.next());
            assertEquals("a2", rs.getString(1));
            assertEquals(0, new BigDecimal("11.00").compareTo(rs.getBigDecimal(2)));
            assertEquals(Timestamp.valueOf(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000)), rs.getTimestamp(3));
        }
        assertEquals(1, count("orders"));
    }

    @Test
    void multiRowUpsertInOneStatement() throws Exception {
        MySqlSink sink = new MySqlSink(target, "t1", Map.of("orders", List.of("id")));
        sink.writeBatch(List.of(row(1, "a", "1.00", null), row(2, "b", "2.00", null)), null);
        assertEquals(2, count("orders"));
    }

    @Test
    void deleteByPk() throws Exception {
        MySqlSink sink = new MySqlSink(target, "t1", Map.of("orders", List.of("id")));
        sink.writeBatch(List.of(row(1, "a", "1.00", null), row(2, "b", "2.00", null)), null);
        RowRecord del = new RowRecord(null, "orders", List.of("id", "name", "amount", "created_at"),
                Arrays.asList(1L, null, null, null), OpType.DELETE, "tx-9");
        sink.writeBatch(List.of(del), null);
        assertEquals(1, count("orders"));
    }

    @Test
    void consecutiveRunsKeepOrderAndGrouping() {
        List<MySqlSink.Run> runs = MySqlSink.splitRuns(List.of(
                new RowRecord(null, "orders", List.of("id"), List.of(1L), OpType.UPSERT, "x"),
                new RowRecord(null, "orders", List.of("id"), List.of(2L), OpType.UPSERT, "x"),
                new RowRecord(null, "items", List.of("id"), List.of(1L), OpType.UPSERT, "x"),
                new RowRecord(null, "orders", List.of("id"), List.of(3L), OpType.DELETE, "x")));
        assertEquals(3, runs.size());
        assertEquals("orders", runs.get(0).table());
        assertTrue(runs.get(0).upsert());
        assertEquals(2, runs.get(0).rows().size());
        assertEquals("items", runs.get(1).table());
        assertFalse(runs.get(2).upsert());
    }

    @Test
    void checkpointUpdatedInSameTransaction() throws Exception {
        MySqlSink sink = new MySqlSink(target, "t1", Map.of("orders", List.of("id")));
        sink.writeBatch(List.of(row(1, "a", "1.00", null)), "3e11:1-5");
        assertEquals("3e11:1-5", TargetSchemaInit.checkpointValue(target, "t1"));
        sink.writeBatch(List.of(row(2, "b", "2.00", null)), "3e11:1-9");
        assertEquals("3e11:1-9", TargetSchemaInit.checkpointValue(target, "t1"));
    }

    @Test
    void failedBatchGoesToDeadLetter() throws Exception {
        MySqlSink sink = new MySqlSink(target, "t1", Map.of("missing_table", List.of("id")));
        RowRecord bad = new RowRecord(null, "missing_table", List.of("id"), List.of(1L), OpType.UPSERT, "tx-1");
        assertThrows(Exception.class, () -> sink.writeBatch(List.of(bad), null));
        try (ResultSet rs = target.createStatement().executeQuery(
                "SELECT COUNT(*) FROM dts_dead_letter")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getLong(1));
        }
    }

    private long count(String table) throws SQLException {
        try (ResultSet rs = target.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
