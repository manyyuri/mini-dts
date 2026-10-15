package com.minidts.connector;

import com.minidts.common.DtsException;
import com.minidts.common.RowRecord;
import com.minidts.common.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MySQL 幂等批量写入。
 *
 * 红线 R4 · 数据与位点同事务：advanceGtidSet 非空时，位点 UPDATE 与业务写入在同一事务内提交，
 * 任一失败整体回滚——这是 at-least-once → 按事务 exactly-once 的分界。
 * 红线 R5 · 顺序：按【连续同表同操作】分组建批，保持全局到达顺序；并行只允许跨任务，
 * 同一任务的批次由调用方串行投递（全量分片间无顺序约束，增量事务间天然有序）。
 * 幂等第二层：INSERT ... ON DUPLICATE KEY UPDATE（前提：目标表主键/唯一键与源一致，任务创建时校验）。
 */
public class MySqlSink implements Sink {

    private final Connection connection;
    private final String taskId;
    private final Map<String, List<String>> pkColumnsByTable;
    private final boolean isMysql;

    public MySqlSink(Connection connection, String taskId, Map<String, List<String>> pkColumnsByTable) {
        this.connection = connection;
        this.taskId = taskId;
        this.pkColumnsByTable = Map.copyOf(pkColumnsByTable);
        try {
            this.isMysql = connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        } catch (SQLException e) {
            throw new DtsException("读取目标库元数据失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void writeBatch(List<RowRecord> batch, String advanceGtidSet) throws SQLException {
        if (batch.isEmpty() && advanceGtidSet == null) return;
        try {
            doWrite(batch, advanceGtidSet);
        } catch (SQLException e) {
            retryPerRowThenDeadLetter(batch, e);
            throw new DtsException("批次写入失败（已重试并落死信）: " + e.getMessage(), e);
        }
    }

    /** 单次事务写入：数据 + 位点同事务（红线 R4）。 */
    private void doWrite(List<RowRecord> batch, String advanceGtidSet) throws SQLException {
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (Run run : splitRuns(batch)) {
                apply(run);
            }
            if (advanceGtidSet != null) {
                TargetSchemaInit.updateCheckpoint(connection, taskId, advanceGtidSet);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    // ── 连续同表同操作分组建批（保持全局顺序） ───────────────────────

    record Run(String table, boolean upsert, List<RowRecord> rows) {}

    static List<Run> splitRuns(List<RowRecord> batch) {
        List<Run> runs = new ArrayList<>();
        for (RowRecord r : batch) {
            boolean upsert = r.op() == com.minidts.common.OpType.UPSERT;
            if (!runs.isEmpty()) {
                Run last = runs.get(runs.size() - 1);
                if (last.table().equals(r.table()) && last.upsert() == upsert) {
                    last.rows().add(r);
                    continue;
                }
            }
            Run run = new Run(r.table(), upsert, new ArrayList<>());
            run.rows().add(r);
            runs.add(run);
        }
        return runs;
    }

    private void apply(Run run) throws SQLException {
        if (run.upsert()) {
            applyUpsert(run.rows());
        } else {
            applyDeletes(run.rows());
        }
    }

    private void applyUpsert(List<RowRecord> rows) throws SQLException {
        RowRecord first = rows.get(0);
        String table = quote(first.table());
        String cols = String.join(",", first.columns().stream().map(MySqlSink::quote).toList());
        String placeholders = "(" + "?,".repeat(first.columns().size() - 1) + "?)";
        String updates = first.columns().stream()
                .map(c -> quote(c) + " = VALUES(" + quote(c) + ")")
                .reduce((a, b) -> a + "," + b).orElse("");
        // H2(MySQL 模式) 只认 VALUES() 形式；MySQL 8 也兼容（deprecation warning 可接受）
        String sql = "INSERT INTO " + table + " (" + cols + ") VALUES "
                + (placeholders + ",").repeat(rows.size() - 1) + placeholders
                + " ON DUPLICATE KEY UPDATE " + updates;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            for (RowRecord r : rows) {
                for (Object v : r.values()) {
                    ps.setObject(i++, Values.normalize(v));
                }
            }
            ps.executeUpdate();
        }
    }

    private void applyDeletes(List<RowRecord> rows) throws SQLException {
        RowRecord first = rows.get(0);
        List<String> pks = pkColumnsByTable.get(first.table());
        if (pks == null || pks.isEmpty()) {
            throw new DtsException("表 " + first.table() + " 缺少主键元数据，无法生成 DELETE");
        }
        String where = pks.stream().map(pk -> quote(pk) + " = ?").reduce((a, b) -> a + " AND " + b).orElse("");
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + quote(first.table()) + " WHERE " + where)) {
            for (RowRecord r : rows) {
                int i = 1;
                for (String pk : pks) {
                    int idx = r.columns().indexOf(pk);
                    if (idx < 0) {
                        throw new DtsException("DELETE 找不到主键列 " + pk + "（表 " + r.table() + " 列漂移？）");
                    }
                    ps.setObject(i++, Values.normalize(r.values().get(idx)));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ── 失败处理：单行重试一次，仍失败落死信 ────────────────────────

    private void retryPerRowThenDeadLetter(List<RowRecord> batch, SQLException cause) {
        for (RowRecord r : batch) {
            try {
                doWrite(List.of(r), null);
            } catch (Exception retryFailure) {
                try {
                    TargetSchemaInit.writeDeadLetter(connection, taskId, r,
                            cause.getMessage() + " | retry: " + retryFailure.getMessage());
                } catch (Exception deadLetterFailure) {
                    System.err.println("[dead-letter-failed] " + deadLetterFailure.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            connection.close();   // Sink 拥有并独占自己的目标连接（worker 级生命周期）
        } catch (SQLException ignored) {
        }
    }

    static String quote(String id) {
        return "`" + id.replace("`", "``") + "`";
    }
}
