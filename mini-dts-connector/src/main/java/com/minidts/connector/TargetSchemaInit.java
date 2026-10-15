package com.minidts.connector;

import com.minidts.common.RowCodec;
import com.minidts.common.RowRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 目标库 DDL：位点表 + 死信表。
 * 红线 R2：dts_incr_checkpoint 必须建在目标库（与业务数据同库，才能同事务）。
 */
public final class TargetSchemaInit {

    private TargetSchemaInit() {}

    public static void ensure(Connection target) throws SQLException {
        try (Statement st = target.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dts_incr_checkpoint (
                      task_id VARCHAR(64) PRIMARY KEY,
                      gtid_set CLOB NOT NULL,
                      updated_at TIMESTAMP
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS dts_dead_letter (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      task_id VARCHAR(64),
                      table_name VARCHAR(128),
                      op VARCHAR(16),
                      tx_id VARCHAR(128),
                      payload CLOB,
                      reason CLOB,
                      created_at TIMESTAMP
                    )""");
        }
    }

    /** 位点写入（由 MySqlSink 在同一事务内调用；MySQL 与 H2 方言各走各的 upsert）。 */
    static void updateCheckpoint(Connection conn, String taskId, String gtidSet) throws SQLException {
        boolean mysql = conn.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");
        String sql = mysql
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
            try (var rs = ps.executeQuery()) {
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
}
