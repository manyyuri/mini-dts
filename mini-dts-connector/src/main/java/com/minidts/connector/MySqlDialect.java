package com.minidts.connector;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * MySQL 目标方言：反引号、{@code ON DUPLICATE KEY UPDATE ... VALUES(c)}。
 *
 * 顺手把「更新集里排除主键列」——原实现会连主键一起 set，语义相同但更啰嗦；
 * 排除之后 MySQL 与 PostgreSQL 的更新集口径一致，方便对照。
 */
public final class MySqlDialect implements SqlDialect {

    @Override
    public String name() {
        return "mysql";
    }

    @Override
    public String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String table(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    @Override
    public String upsert(String schema, String table, List<String> columns, List<String> pkColumns, int rows) {
        String placeholders = SqlDialect.placeholders(columns.size());
        String updates = updateSet(columns, pkColumns);
        return "INSERT INTO " + table(schema, table)
               + " (" + SqlDialect.joinQuoted(columns, this) + ") VALUES "
               + (placeholders + ",").repeat(Math.max(0, rows - 1)) + placeholders
               + " ON DUPLICATE KEY UPDATE " + updates;
    }

    @Override
    public String delete(String schema, String table, List<String> pkColumns) {
        String where = pkColumns.stream().map(pk -> quote(pk) + " = ?").reduce((a, b) -> a + " AND " + b).orElse("");
        return "DELETE FROM " + table(schema, table) + " WHERE " + where;
    }

    @Override
    public String adminUpsert(String schema, String table, List<String> keyColumns,
                              LinkedHashMap<String, String> columnExprs) {
        String cols = String.join(",", columnExprs.keySet().stream().map(this::quote).toList());
        String values = String.join(",", columnExprs.values());
        String updates = columnExprs.keySet().stream()
                .filter(c -> !keyColumns.contains(c))
                .map(c -> quote(c) + " = VALUES(" + quote(c) + ")")
                .reduce((a, b) -> a + "," + b)
                .orElseGet(() -> quote(keyColumns.get(0)) + " = " + quote(keyColumns.get(0)));
        return "INSERT INTO " + table(schema, table) + "(" + cols + ") VALUES (" + values + ")"
               + " ON DUPLICATE KEY UPDATE " + updates;
    }

    @Override
    public String longTextType() {
        return "LONGTEXT";
    }

    @Override
    public String autoIncrementPk() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    public int maxRowsPerStatement() {
        return 500;
    }

    /** 非主键列才进更新集；全是主键时退化成「原地赋同值」，保证语句合法。 */
    private String updateSet(List<String> columns, List<String> pkColumns) {
        String set = columns.stream()
                .filter(c -> !pkColumns.contains(c))
                .map(c -> quote(c) + " = VALUES(" + quote(c) + ")")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        if (!set.isEmpty()) {
            return set;
        }
        String pk = pkColumns.isEmpty() ? columns.get(0) : pkColumns.get(0);
        return quote(pk) + " = " + quote(pk);
    }
}
