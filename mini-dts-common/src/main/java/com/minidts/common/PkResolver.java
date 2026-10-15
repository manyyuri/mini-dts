package com.minidts.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主键列解析（MySQL 与 H2(MySQL 模式) 均可），创建任务时同时用于源/目标主键一致性校验。
 */
public final class PkResolver {

    private PkResolver() {}

    public static List<String> resolvePkColumns(Connection conn, String schema, String table) {
        List<String> schemaCandidates = schema == null ? List.of("") : candidates(schema);
        for (String candidate : candidates(table)) {
            for (String schemaCandidate : schemaCandidates) {
                List<String> cols = tryQuery(conn, schemaCandidate, candidate);
                if (!cols.isEmpty()) return cols;
            }
        }
        throw new DtsException("无法解析主键（或表没有主键）: " + schema + "." + table
                + " —— 无主键表不支持断点续传，本系统明确拒绝");
    }

    /** 解析主键列 + 主键 JDBC 类型名（用于断点解码）。 */
    public static String pkTypeName(Connection conn, String schema, String table) {
        List<String> pks = resolvePkColumns(conn, schema, table);
        String col = pks.get(0);
        for (String candidate : candidates(table)) {
            String sql = "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME=? AND COLUMN_NAME=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, candidate);
                ps.setString(2, col);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {
                // 尝试下一个候选
            }
        }
        throw new DtsException("无法解析主键列类型: " + schema + "." + table + "." + col);
    }

    /** 解析表的全列名（按 ORDINAL_POSITION）——binlog 反序列化后的 Serializable[] 按此对齐。 */
    public static List<String> resolveColumns(Connection conn, String schema, String table) {
        for (String candidate : candidates(table)) {
            String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? "
                    + (schema == null ? "" : "AND TABLE_SCHEMA = ? ")
                    + "ORDER BY ORDINAL_POSITION";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, candidate);
                if (schema != null) {
                    for (String sc : candidates(schema)) {
                        ps.setString(2, sc);
                        break; // H2/MySQL 大小写差异由 TABLE_NAME 侧已覆盖；schema 一般小写一致
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<String> cols = new ArrayList<>();
                    while (rs.next()) cols.add(rs.getString(1));
                    if (!cols.isEmpty()) return cols;
                }
            } catch (SQLException ignored) {
                // 尝试下一个候选
            }
        }
        throw new DtsException("无法解析列名: " + schema + "." + table);
    }

    private static List<String> tryQuery(Connection conn, String schema, String table) {
        String sql = """
                SELECT k.COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k
                JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS c
                  ON k.CONSTRAINT_NAME = c.CONSTRAINT_NAME
                 AND k.TABLE_SCHEMA = c.TABLE_SCHEMA
                 AND k.TABLE_NAME = c.TABLE_NAME
                WHERE c.CONSTRAINT_TYPE = 'PRIMARY KEY' AND k.TABLE_NAME = ?
                ORDER BY k.ORDINAL_POSITION
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> cols = new ArrayList<>();
                while (rs.next()) cols.add(rs.getString(1));
                return cols;
            }
        } catch (SQLException e) {
            return List.of();
        }
    }

    private static List<String> candidates(String name) {
        if (name == null) return List.of();
        return List.of(name, name.toUpperCase(), name.toLowerCase());
    }
}
