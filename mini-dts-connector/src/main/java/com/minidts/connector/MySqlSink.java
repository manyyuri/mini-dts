package com.minidts.connector;

import com.minidts.common.DtsException;
import com.minidts.common.OpType;
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
 * 幂等第二层：INSERT... ON DUPLICATE KEY UPDATE（前提：目标表主键/唯一键与源一致，建任务时校验）。
 *
 * 库名：{@code targetSchema} 非空时所有语句都写全限定名 —— 目标库由任务显式声明并校验过，
 * 不再依赖连接 URL 的默认库（否则 URL 与任务 schema 不一致会静默写错库）。
 */
public class MySqlSink implements Sink {

    /** 单条 INSERT 最大行数：再大就撞 max_allowed_packet，一个事务几万行时必须切片。 */
    static final int MAX_ROWS_PER_STATEMENT = 500;

    private final Connection connection;
    private final String taskId;
    private final String targetSchema;
    private final Map<String, List<String>> pkColumnsByTable;

    /** 兼容旧用法（H2 单测：库名由连接决定，不加限定）。 */
    public MySqlSink(Connection connection, String taskId, Map<String, List<String>> pkColumnsByTable) {
        this(connection, null, taskId, pkColumnsByTable);
    }

    public MySqlSink(Connection connection, String targetSchema, String taskId,
                     Map<String, List<String>> pkColumnsByTable) {
        this.connection = connection;
        this.taskId = taskId;
        this.targetSchema = targetSchema;
        this.pkColumnsByTable = Map.copyOf(pkColumnsByTable);
    }

    @Override
    public void writeBatch(List<RowRecord> batch, String advanceGtidSet) throws SQLException {
        if (batch.isEmpty() && advanceGtidSet == null) {
            return;
        }
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

    record Run(String table, boolean upsert, List<RowRecord> rows) {
    }

    static List<Run> splitRuns(List<RowRecord> batch) {
        List<Run> runs = new ArrayList<>();
        for (RowRecord r : batch) {
            boolean upsert = r.op() == OpType.UPSERT;
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
            for (int i = 0; i < run.rows().size(); i += MAX_ROWS_PER_STATEMENT) {
                applyUpsert(run.rows().subList(i, Math.min(run.rows().size(), i + MAX_ROWS_PER_STATEMENT)));
            }
        } else {
            applyDeletes(run.rows());
        }
    }

    private void applyUpsert(List<RowRecord> rows) throws SQLException {
        RowRecord first = rows.get(0);
        String table = qualify(first.table());
        String cols = String.join(",", first.columns().stream().map(MySqlSink::quote).toList());
        String placeholders = "(" + "?,".repeat(first.columns().size() - 1) + "?)";
        String updates = first.columns().stream()
                .map(c -> quote(c) + " = VALUES(" + quote(c) + ")")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
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
                "DELETE FROM " + qualify(first.table()) + " WHERE " + where)) {
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

    private String qualify(String table) {
        return targetSchema == null ? quote(table) : quote(targetSchema) + "." + quote(table);
    }

    static String quote(String id) {
        return "`" + id.replace("`", "``") + "`";
    }
}
