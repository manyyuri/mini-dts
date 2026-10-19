package com.minidts.connector;

import com.minidts.common.DdlEvent;
import com.minidts.common.RowCodec;
import com.minidts.common.RowRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 目标库自用表 DDL（全部建在目标库，与业务数据同库）。
 *
 * 红线 R2：dts_incr_checkpoint 必须建在目标库（与业务数据同库，才能同事务）。
 * 断点类数据也统一放目标库（dts_full_checkpoint）—— 这样任务换节点、换进程都能接着跑，
 * 不会被控制面所在机器绑死。
 */
public final class TargetSchemaInit {

    private TargetSchemaInit() {
    }

    /**
     * 建表（幂等）。{@code longText} 按目标库方言取：MySQL 没有 CLOB 类型，
     * 老实现固定写 CLOB 在真实 MySQL 上建表会失败（H2 单测发现不了）。
     */
    public static void ensure(Connection target) throws SQLException {
        boolean mysql = isMysql(target);
        String longText = mysql ? "LONGTEXT" : "CLOB";
        String idType = "BIGINT AUTO_INCREMENT PRIMARY KEY";
        try (Statement st = target.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dts_incr_checkpoint (
                      task_id VARCHAR(64) PRIMARY KEY,
                      gtid_set %s NOT NULL,
                      updated_at TIMESTAMP
                    )""".formatted(longText));
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dts_dead_letter (
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
                    CREATE TABLE IF NOT EXISTS dts_full_checkpoint (
                      task_id VARCHAR(64),
                      upper_pk VARCHAR(256),
                      done_at TIMESTAMP,
                      CONSTRAINT pk_dts_full_checkpoint PRIMARY KEY (task_id, upper_pk)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dts_ddl_event (
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
                    CREATE TABLE IF NOT EXISTS dts_schema_fingerprint (
                      task_id VARCHAR(64),
                      table_name VARCHAR(128),
                      fingerprint VARCHAR(64),
                      updated_at TIMESTAMP,
                      CONSTRAINT pk_dts_schema_fingerprint PRIMARY KEY (task_id, table_name)
                    )""");
        }
    }

    static boolean isMysql(Connection conn) throws SQLException {
        return conn.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
    }

    /** 位点写入（由 MySqlSink 在同一事务内调用；MySQL 与 H2 方言各走各的 upsert）。 */
    static void updateCheckpoint(Connection conn, String taskId, String gtidSet) throws SQLException {
        String sql = isMysql(conn)
                ? "INSERT INTO dts_incr_checkpoint(task_id, gtid_set, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                  + "ON DUPLICATE KEY UPDATE gtid_set = VALUES(gtid_set), updated_at = CURRENT_TIMESTAMP"
                : "MERGE INTO dts_incr_checkpoint(task_id, gtid_set, updated_at) KEY(task_id) "
                  + "VALUES (?, ?, CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, gtidSet);
            ps.executeUpdate();
        }
    }

    public static String checkpointValue(Connection target, String taskId) throws SQLException {
        try (PreparedStatement ps = target.prepareStatement(
                "SELECT gtid_set FROM dts_incr_checkpoint WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    static void writeDeadLetter(Connection target, String taskId, RowRecord r, String reason) {
        try (PreparedStatement ps = target.prepareStatement("""
                INSERT INTO dts_dead_letter(task_id, table_name, op, tx_id, payload, reason, created_at)
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
                INSERT INTO dts_ddl_event(task_id, kind, schema_name, table_name, gtid, applied, reason, statement, created_at)
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
