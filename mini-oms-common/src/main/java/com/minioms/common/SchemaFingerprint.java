package com.minioms.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 表结构指纹：列名 + 类型 + 可空性 + 顺序 的稳定哈希。
 *
 * 用途：建任务时把源端指纹登记到目标库，增量启动/重连前复核 ——
 * 源端被 DBA 手工改了结构的场景（binlog 里看不到、或 DDL 已过保留期）能在开始写之前暴露。
 *
 * 关键设计：指纹按**类型等价类**算（{@link TypeFamily}），不比原始类型名。
 * 否则 `BIGINT` 与 `int8`、`VARCHAR` 与 `character varying` 会被判成「结构不一致」，
 * 跨方言场景天天误报。
 */
public final class SchemaFingerprint {

    private SchemaFingerprint() {
    }

    /** 纯函数：从「列名:类型:可空」有序清单算指纹（便于单测）。 */
    public static String of(List<String> columnSpecs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(String.join("|", columnSpecs).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new OmsException("SHA-256 不可用: " + e.getMessage(), e);
        }
    }

    /** 读某张表的指纹：按「列名 + 类型等价类 + 可空性」计算，跨方言可比。 */
    public static String read(Connection conn, String schema, String table) {
        List<String> specs = specs(conn, schema, table);
        if (specs.isEmpty()) {
            throw new OmsException("表不存在或没有列: " + schema + "." + table);
        }
        return of(specs);
    }

    private static List<String> specs(Connection conn, String schema, String table) {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = ?" + (schema == null ? "" : " AND TABLE_SCHEMA = ?")
                + " ORDER BY ORDINAL_POSITION";
        List<String> specs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            if (schema != null) {
                ps.setString(2, schema);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TypeFamily family = ColumnType.parse(rs.getString(2)).family();
                    specs.add(rs.getString(1) + ":" + family + ":" + rs.getString(3));
                }
            }
        } catch (SQLException e) {
            throw new OmsException("读取表结构指纹失败 " + schema + "." + table + ": " + e.getMessage(), e);
        }
        return specs;
    }

    /** 列清单（用于差异提示，不止给一个哈希值）。 */
    public static List<String> columns(Connection conn, String schema, String table) {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = ?" + (schema == null ? "" : " AND TABLE_SCHEMA = ?")
                + " ORDER BY ORDINAL_POSITION";
        List<String> specs = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            if (schema != null) {
                ps.setString(2, schema);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColumnType type = ColumnType.parse(rs.getString(2));
                    specs.add(rs.getString(1) + ":" + type.raw() + "(" + type.family() + ")" + ":" + rs.getString(3));
                }
            }
        } catch (SQLException e) {
            throw new OmsException("读取表结构失败 " + schema + "." + table + ": " + e.getMessage(), e);
        }
        return specs;
    }
}
