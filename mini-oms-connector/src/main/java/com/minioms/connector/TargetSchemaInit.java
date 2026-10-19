package com.minioms.connector;

import com.minioms.common.DdlEvent;
import com.minioms.common.RowCodec;
import com.minioms.common.RowRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 目标库自用表 DDL（全部建在目标库，与业务数据同库）。
 *
 * 红线 R2：oms_incr_checkpoint 必须建在目标库（与业务数据同库，才能同事务）。
 * 断点类数据也统一放目标库（oms_full_checkpoint）—— 换节点、换进程都能接着跑。
 *
 * 列类型与 upsert 语法全部交给 {@link SqlDialect}，自己不再判断「是不是 MySQL」。
 */
public final class TargetSchemaInit {

    private TargetSchemaInit() {
    }

    public static void ensure(Connection target) throws SQLException {
        ensure(target, SqlDialectFactory.detect(target));
    }

    public static void ensure(Connection target, SqlDialect dialect) throws SQLException {
        String longText = dialect.longTextType();
        String idType = dialect.autoIncrementPk();
        try (Statement st = target.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS oms_incr_checkpoint (
                      task_id VARCHAR(64) PRIMARY KEY,
                      gtid_set %s NOT NULL,
                      updated_at TIMESTAMP
                    )""".formatted(longText));
            st.execute("""
                    CREATE TABLE IF NOT EXISTS oms_dead_letter (
                      id %s,
                      task_id VARCHAR(64),
                      table_name VARCHAR(128),
                      op VARCHAR(16),
                      tx_id VARCHAR(128),
                      payload %s,
                      reason %s,
                      created_at TIMESTAMP
                    )""".formatted(idType, longText, longText));
            st.execute("""
                    CREATE TABLE IF NOT EXISTS oms_full_checkpoint (
                      task_id VARCHAR(64),
                      upper_pk VARCHAR(256),
                      done_at TIMESTAMP,
                      CONSTRAINT pk_oms_full_checkpoint PRIMARY KEY (task_id, upper_pk)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS oms_ddl_event (
                      id %s,
                      task_id VARCHAR(64),
                      kind VARCHAR(32),
                      schema_name VARCHAR(64),
                      table_name VARCHAR(128),
                      gtid VARCHAR(128),
                      applied BOOLEAN,
                      reason VARCHAR(2000),
                      statement %s,
                      created_at TIMESTAMP
                    )""".formatted(idType, longText));
            st.execute("""
                    CREATE TABLE IF NOT EXISTS oms_schema_fingerprint (
                      task_id VARCHAR(64),
                      table_name VARCHAR(128),
                      fingerprint VARCHAR(64),
                      updated_at TIMESTAMP,
                      CONSTRAINT pk_oms_schema_fingerprint PRIMARY KEY (task_id, table_name)
                    )""");
        }
    }

    /** 位点写入（由 MySqlSink 在同一事务内调用）。 */
    static void updateCheckpoint(Connection conn, String taskId, String gtidSet) throws SQLException {
        updateCheckpoint(conn, taskId, gtidSet, SqlDialectFactory.detect(conn));
    }

    static void updateCheckpoint(Connection conn, String taskId, String gtidSet, SqlDialect dialect)
            throws SQLException {
        LinkedHashMap<String, String> exprs = new LinkedHashMap<>();
        exprs.put("task_id", "?");
        exprs.put("gtid_set", "?");
        exprs.put("updated_at", "CURRENT_TIMESTAMP");
        try (PreparedStatement ps = conn.prepareStatement(
                dialect.adminUpsert(null, "oms_incr_checkpoint", List.of("task_id"), exprs))) {
            ps.setString(1, taskId);
            ps.setString(2, gtidSet);
            ps.executeUpdate();
        }
    }

    public static String checkpointValue(Connection target, String taskId) throws SQLException {
        try (PreparedStatement ps = target.prepareStatement(
                "SELECT gtid_set FROM oms_incr_checkpoint WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static void writeDeadLetter(Connection target, String taskId, RowRecord r, String reason) {
        try (PreparedStatement ps = target.prepareStatement("""
                INSERT INTO oms_dead_letter(task_id, table_name, op, tx_id, payload, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            ps.setString(1, taskId);
            ps.setString(2, r.table());
            ps.setString(3, r.op().name());
            ps.setString(4, r.txId());
            ps.setString(5, RowCodec.encode(r));
            ps.setString(6, reason);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 死信本身写不进去只能打日志——不能因为它中断主链路重试语义
            System.err.println("[dead-letter-failed] " + r.table() + " " + r.txId() + ": " + e.getMessage());
        }
    }

    static void insertDdlEvent(Connection target, DdlEvent e) throws SQLException {
        try (PreparedStatement ps = target.prepareStatement("""
                INSERT INTO oms_ddl_event(task_id, kind, schema_name, table_name, gtid, applied, reason, statement, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            ps.setString(1, e.taskId());
            ps.setString(2, e.kind());
            ps.setString(3, e.schema());
            ps.setString(4, e.table());
            ps.setString(5, e.gtid());
            ps.setBoolean(6, e.applied());
            ps.setString(7, e.reason());
            ps.setString(8, e.sql());
            ps.executeUpdate();
        }
    }
}
