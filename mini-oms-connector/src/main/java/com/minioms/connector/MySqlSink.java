package com.minioms.connector;

import com.minioms.common.OmsException;
import com.minioms.common.OpType;
import com.minioms.common.RowRecord;
import com.minioms.common.Values;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 幂等批量写入：**只负责数据面职责** —— 事务、批次切分、位点、死信。
 * SQL 长什么样交给 {@link SqlDialect}（MySQL / PostgreSQL / Oracle 各一份实现）。
 *
 * 这样切的意义：加一个目标库 = 新增一个方言类，不必再碰事务与位点这段最容易改坏的代码。
 *
 * 红线 R4 · 数据与位点同事务：advanceGtidSet 非空时，位点 UPDATE 与业务写入在同一事务内提交。
 * 红线 R5 · 顺序：按【连续同表同操作】分组建批，保持全局到达顺序。
 */
public class MySqlSink implements Sink {

    private final Connection connection;
    private final String taskId;
    private final String targetSchema;
    private final Map<String, List<String>> pkColumnsByTable;
    private final SqlDialect dialect;

    /** 兼容旧用法（方言按连接自动探测）。 */
    public MySqlSink(Connection connection, String taskId, Map<String, List<String>> pkColumnsByTable) {
        this(connection, null, taskId, pkColumnsByTable, SqlDialectFactory.detect(connection));
    }

    public MySqlSink(Connection connection, String targetSchema, String taskId,
                     Map<String, List<String>> pkColumnsByTable) {
        this(connection, targetSchema, taskId, pkColumnsByTable, SqlDialectFactory.detect(connection));
    }

    /** 显式指定方言（对照测试 / 需要覆盖探测逻辑时用）。 */
    public MySqlSink(Connection connection, String targetSchema, String taskId,
                     Map<String, List<String>> pkColumnsByTable, SqlDialect dialect) {
        this.connection = connection;
        this.taskId = taskId;
        this.targetSchema = targetSchema;
        this.pkColumnsByTable = Map.copyOf(pkColumnsByTable);
        this.dialect = dialect;
    }

    public SqlDialect dialect() {
        return dialect;
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
            throw new OmsException("批次写入失败（已重试并落死信）: " + e.getMessage(), e);
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
                TargetSchemaInit.updateCheckpoint(connection, taskId, advanceGtidSet, dialect);
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
            int limit = dialect.maxRowsPerStatement();
            for (int i = 0; i < run.rows().size(); i += limit) {
                applyUpsert(run.rows().subList(i, Math.min(run.rows().size(), i + limit)));
            }
        } else {
            applyDeletes(run.rows());
        }
    }

    private void applyUpsert(List<RowRecord> rows) throws SQLException {
        RowRecord first = rows.get(0);
        List<String> pk = pkColumnsByTable.getOrDefault(first.table(), List.of(first.columns().get(0)));
        String sql = dialect.upsert(targetSchema, first.table(), first.columns(), pk, rows.size());
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
            throw new OmsException("表 " + first.table() + " 缺少主键元数据，无法生成 DELETE");
        }
        try (PreparedStatement ps = connection.prepareStatement(
                dialect.delete(targetSchema, first.table(), pks))) {
            for (RowRecord r : rows) {
                int i = 1;
                for (String pk : pks) {
                    int idx = r.columns().indexOf(pk);
                    if (idx < 0) {
                        throw new OmsException("DELETE 找不到主键列 " + pk + "（表 " + r.table() + " 列漂移？）");
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
}
