package com.minioms.connector;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * H2（MySQL 模式）方言：用于单测与本地验证。
 *
 * 与 MySQL 的差别只在两处，恰好说明「方言差异是具体的、可穷举的」：
 * <ul>
 *   <li>自用表 upsert 用 {@code MERGE INTO ... KEY(...)}（H2 不认 ON DUPLICATE 的 VALUES() 形式在所有版本）</li>
 *   <li>长文本用 {@code CLOB}（H2 没有 LONGTEXT）</li>
 * </ul>
 */
public final class H2Dialect implements SqlDialect {

    private final MySqlDialect dml = new MySqlDialect();

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public String quote(String identifier) {
        return dml.quote(identifier);
    }

    @Override
    public String table(String schema, String table) {
        return dml.table(schema, table);
    }

    @Override
    public String upsert(String schema, String table, List<String> columns, List<String> pkColumns, int rows) {
        return dml.upsert(schema, table, columns, pkColumns, rows);
    }

    @Override
    public String delete(String schema, String table, List<String> pkColumns) {
        return dml.delete(schema, table, pkColumns);
    }

    @Override
    public String adminUpsert(String schema, String table, List<String> keyColumns,
                              LinkedHashMap<String, String> columnExprs) {
        String cols = String.join(",", columnExprs.keySet().stream().map(this::quote).toList());
        String values = String.join(",", columnExprs.values());
        String keys = SqlDialect.joinQuoted(keyColumns, this);
        return "MERGE INTO " + table(schema, table) + "(" + cols + ") KEY(" + keys + ") VALUES (" + values + ")";
    }

    @Override
    public String longTextType() {
        return "CLOB";
    }

    @Override
    public String autoIncrementPk() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    public int maxRowsPerStatement() {
        return 500;
    }
}
