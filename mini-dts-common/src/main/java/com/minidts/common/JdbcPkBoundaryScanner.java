package com.minidts.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 主键边界扫描：流式读取全部主键值，按 chunkSize 聚合成 (lower, upper] 分片。
 *
 * 红线 R6：全量迁移与数据校验【必须】共用本类 + 同一 chunkSize，否则两端 chunk 对不上。
 */
public final class JdbcPkBoundaryScanner {

    private JdbcPkBoundaryScanner() {}

    /** 返回分片上限列表（每个元素是一个 chunk 的 upperInclusive）。 */
    public static List<Object> scanUpperBounds(Connection conn, String schema, String table,
                                               String pkColumn, int chunkSize) {
        String q = qualify(schema, table);
        String sql = "SELECT " + quote(pkColumn) + " FROM " + q + " ORDER BY " + quote(pkColumn);
        List<Object> uppers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            streamFetch(conn, ps);
            try (ResultSet rs = ps.executeQuery()) {
                int n = 0;
                Object last = null;
                while (rs.next()) {
                    last = rs.getObject(1);
                    n++;
                    if (n >= chunkSize) {
                        uppers.add(last);
                        n = 0;
                    }
                }
                if (n > 0) uppers.add(last);
            }
        } catch (SQLException e) {
            throw new DtsException("主键边界扫描失败 " + q + ": " + e.getMessage(), e);
        }
        return uppers;
    }

    public static String qualify(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    public static String quote(String id) {
        return "`" + id.replace("`", "``") + "`";
    }

    static void streamFetch(Connection conn, PreparedStatement ps) throws SQLException {
        if (conn.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")) {
            ps.setFetchSize(Integer.MIN_VALUE); // MySQL 流式读取
        } else {
            ps.setFetchSize(500);
        }
    }
}
