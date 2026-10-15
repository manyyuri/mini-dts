package com.minidts.full;

import com.minidts.common.DtsException;
import com.minidts.common.FullCheckpointStore;
import com.minidts.common.TaskStateNotifier;
import com.minidts.connector.MySqlSink;
import com.minidts.connector.Sink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v0 验收（H2 双库）：断点续传——模拟“数据已提交、断点未落”的中途崩溃，
 * 重启后从断点续跑，最终源/目标完全一致且不重复。
 */
class FullTransferH2Test {

    Connection source;
    Connection target;
    MemoryCheckpoint checkpoints;
    RecordingNotifier notifier;

    static final String SRC_URL = "jdbc:h2:mem:fullsrc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
    static final String TGT_URL = "jdbc:h2:mem:fulltgt;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";

    @BeforeEach
    void setUp() throws Exception {
        source = DriverManager.getConnection(SRC_URL, "sa", "");
        target = DriverManager.getConnection(TGT_URL, "sa", "");
        try (Statement st = source.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
            for (long i = 1; i <= 100; i++) {
                st.execute("INSERT INTO orders VALUES (" + i + ", 'name-" + i + "', " + (i * 1.5) + ")");
            }
        }
        try (Statement st = target.createStatement()) {
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
        }
        checkpoints = new MemoryCheckpoint();
        notifier = new RecordingNotifier();
    }

    @AfterEach
    void tearDown() throws SQLException {
        source.close();
        target.close();
    }

    /** 在第 N 个批次后“提交数据但抛异常”——精确模拟断点未落的崩溃点（计数器跨 sink 实例共享：每个 chunk 新开 sink）。 */
    static class CrashAfterCommitSink implements Sink {
        final Sink delegate;
        final java.util.concurrent.atomic.AtomicInteger written;
        final int crashAtBatch;

        CrashAfterCommitSink(Sink delegate, java.util.concurrent.atomic.AtomicInteger written, int crashAtBatch) {
            this.delegate = delegate;
            this.written = written;
            this.crashAtBatch = crashAtBatch;
        }

        @Override
        public void writeBatch(List<com.minidts.common.RowRecord> batch, String gtid) throws SQLException {
            delegate.writeBatch(batch, gtid);
            if (written.incrementAndGet() == crashAtBatch) {
                throw new DtsException("simulated crash: data committed, checkpoint NOT written");
            }
        }
    }

    @Test
    void crashMidWayThenResumeEndsConsistent() throws Exception {
        // 第一次运行：跑到第 5 个分片后“崩溃”
        java.util.concurrent.atomic.AtomicInteger written = new java.util.concurrent.atomic.AtomicInteger();
        FullTransferService first = new FullTransferService(
                "t1", ep(SRC_URL), "public", "orders", 7, 2, 100_000,
                checkpoints, () -> new CrashAfterCommitSink(newSink(), written, 5), notifier);
        first.run();
        assertTrue(notifier.errors.size() >= 1, "第一次运行应报错");
        assertTrue(first.chunksDone.get() >= 1, "崩溃前应有已断点分片");
        assertTrue(first.chunksSkipped.get() == 0);

        long targetAfterCrash = count(target, "orders");
        assertTrue(targetAfterCrash > 0 && targetAfterCrash < 100, "崩溃应发生在中途");

        // 第二次运行：从断点续跑（新一轮用干净的错误记录）
        notifier.errors.clear();
        FullTransferService second = new FullTransferService(
                "t1", ep(SRC_URL), "public", "orders", 7, 2, 100_000,
                checkpoints, this::newSink, notifier);
        second.run();
        assertEquals(0, notifier.errors.size(), "第二次运行不应报错: " + notifier.errors);
        assertTrue(second.chunksSkipped.get() >= 1, "应跳过已完成分片");
        assertEquals(100, count(target, "orders"), "目标库行数应与源一致");
        assertEquals(1, notifier.fullDoneCount);

        // 字段级一致
        List<String> srcRows = dump(source, "orders");
        List<String> tgtRows = dump(target, "orders");
        assertEquals(srcRows, tgtRows);
    }

    @Test
    void rerunFromScratchIsIdempotent() throws Exception {
        FullTransferService run1 = new FullTransferService(
                "t2", ep(SRC_URL), "public", "orders", 20, 2, 100_000,
                new MemoryCheckpoint(), this::newSink, notifier);
        run1.run();
        FullTransferService run2 = new FullTransferService(
                "t2", ep(SRC_URL), "public", "orders", 20, 2, 100_000,
                new MemoryCheckpoint(), this::newSink, notifier);
        run2.run(); // 无断点全量重跑
        assertEquals(100, count(target, "orders"), "重复全量不产生重复行（幂等）");
    }

    private Sink newSink() throws SQLException {
        Connection c = DriverManager.getConnection(TGT_URL, "sa", "");
        return new MySqlSink(c, "t1", Map.of("orders", List.of("id")));
    }

    private static com.minidts.common.DbEndpoint ep(String url) {
        return new com.minidts.common.DbEndpoint(url, "sa", "");
    }

    static long count(Connection c, String table) throws SQLException {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static List<String> dump(Connection c, String table) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT id, name, amount FROM " + table + " ORDER BY id")) {
            while (rs.next()) {
                rows.add(rs.getLong(1) + "|" + rs.getString(2) + "|" + rs.getBigDecimal(3).toPlainString());
            }
        }
        return rows;
    }

    static class MemoryCheckpoint implements FullCheckpointStore {
        final Set<String> done = new HashSet<>();

        @Override
        public synchronized void markChunkDone(String taskId, String upperPk) {
            done.add(taskId + "|" + upperPk);
        }

        @Override
        public synchronized Set<String> completedUpperPks(String taskId) {
            Set<String> result = new HashSet<>();
            for (String s : done) {
                if (s.startsWith(taskId + "|")) result.add(s.substring(taskId.length() + 1));
            }
            return result;
        }
    }

    static class RecordingNotifier implements TaskStateNotifier {
        final List<String> errors = new CopyOnWriteArrayList<>();
        volatile int fullDoneCount = 0;

        @Override
        public void onFullDone(String taskId) {
            fullDoneCount++;
        }

        @Override
        public void onError(String taskId, String reason) {
            errors.add(reason);
        }
    }
}
