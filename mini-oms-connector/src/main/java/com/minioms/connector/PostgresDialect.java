package com.minioms.connector;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * PostgreSQL 目标方言：双引号、{@code ON CONFLICT (pk) DO UPDATE SET c = EXCLUDED.c}。
 *
 * 三处与 MySQL 结构性不同，也正是「异构不是翻译 SQL」的证据：
 * <ol>
 *   <li><b>冲突目标必须显式</b>：{@code ON CONFLICT} 后面必须写冲突列（主键），
 *       而 MySQL 的 {@code ON DUPLICATE KEY} 由库自己判断唯一键 —— 所以端口签名必须带 pkColumns。</li>
 *   <li><b>更新值来源是 EXCLUDED</b>（本次待插入的行），不是 {@code VALUES(col)}。</li>
 *   <li><b>没有自有占位符语法差异</b>：JDBC 层仍然用 {@code ?}，所以不必改 Sink 的绑定代码。</li>
 * </ol>
 *
 * 本类只生成 SQL，不连库 —— 用来在单测里和 MySQL 方言做「结构对齐」。
 */
public final class PostgresDialect implements SqlDialect {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String table(String schema, String table) {
        return schema == null || schema.isBlank() ? quote(table) : quote(schema) + "." + quote(table);
    }

    @Override
    public String upsert(String schema, String table, List<String> columns, List<String> pkColumns, int rows) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalStateException("PostgreSQL 的 ON CONFLICT 需要显式冲突目标（主键列）");
        }
        String placeholders = SqlDialect.placeholders(columns.size());
        String conflictTarget = SqlDialect.joinQuoted(pkColumns, this);
        String updates = columns.stream()
                .filter(c -> !pkColumns.contains(c))
                .map(c -> quote(c) + " = EXCLUDED." + quote(c))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String action = updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + updates;
        return "INSERT INTO " + table(schema, table)
               + " (" + SqlDialect.joinQuoted(columns, this) + ") VALUES "
               + (placeholders + ",").repeat(Math.max(0, rows - 1)) + placeholders
               + " ON CONFLICT (" + conflictTarget + ") " + action;
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
        String conflictTarget = SqlDialect.joinQuoted(keyColumns, this);
        String updates = columnExprs.keySet().stream()
                .filter(c -> !keyColumns.contains(c))
                .map(c -> quote(c) + " = EXCLUDED." + quote(c))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String action = updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + updates;
        return "INSERT INTO " + table(schema, table) + "(" + cols + ") VALUES (" + values + ")"
               + " ON CONFLICT (" + conflictTarget + ") " + action;
    }

    @Override
    public String longTextType() {
        return "TEXT";
    }

    @Override
    public String autoIncrementPk() {
        return "BIGSERIAL PRIMARY KEY";
    }

    @Override
    public int maxRowsPerStatement() {
        return 500;
    }
}
