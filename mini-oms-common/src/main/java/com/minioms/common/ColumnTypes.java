package com.minioms.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 读一张表的列类型（MySQL / H2 / PostgreSQL 的 information_schema 都够用）。 */
public final class ColumnTypes {

    private ColumnTypes() {
    }

    public static List<ColumnType> read(Connection conn, String schema, String table) {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_NAME = ?" + (schema == null ? "" : " AND TABLE_SCHEMA = ?")
                + " ORDER BY ORDINAL_POSITION";
        List<ColumnType> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            if (schema != null) {
                ps.setString(2, schema);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(ColumnType.parse(rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new OmsException("读取列类型失败 " + schema + "." + table + ": " + e.getMessage(), e);
        }
        return out;
    }

    /** 列名 → 类型（按定义顺序）。 */
    public static List<String> columnNames(Connection conn, String schema, String table) {
        return PkResolver.resolveColumns(conn, schema, table);
    }
}
