package com.minioms.connector;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 目标库方言端口：把「SQL 长什么样」从写入逻辑里剥出来。
 *
 * 只有四个职责，故意不更多 —— 方法一多就说明抽象错了：
 * <ol>
 *   <li>{@link #table} 标识符引用与限定名（反引号 / 双引号 / 大小写折叠）</li>
 *   <li>{@link #upsert} 幂等写入语句（这条是异构的真正难点）</li>
 *   <li>{@link #delete} 按主键删除</li>
 *   <li>{@link #adminUpsert} 自用表（位点 / 指纹）的 upsert</li>
 * </ol>
 *
 * 注意 {@link #upsert} 必须接收主键列：PostgreSQL 的 {@code ON CONFLICT} 需要显式冲突目标，
 * MySQL 不需要。**这一个签名差异，就是「为什么按目标类型分实现写入端」的最小证据。**
 */
public interface SqlDialect {

    String name();

    /** 限定表名：schema 为空时只返回表名。 */
    String table(String schema, String table);

    /**
     * 幂等写入（多行 VALUES）。占位符数量 = columns.size() * rows。
     *
     * @param pkColumns 主键列，供需要「显式冲突目标」的方言使用
     */
    String upsert(String schema, String table, List<String> columns, List<String> pkColumns, int rows);

    /** 按主键删除（单行），由调用方 addBatch。 */
    String delete(String schema, String table, List<String> pkColumns);

    /**
     * 自用表 upsert。{@code columnExprs} 是「列名 → 值表达式」（如 {@code "?"} 或 {@code "CURRENT_TIMESTAMP"}）。
     */
    String adminUpsert(String schema, String table, List<String> keyColumns,
                       LinkedHashMap<String, String> columnExprs);

    /** 长文本列类型（自用表 DDL）。 */
    String longTextType();

    /** 自增主键列定义（自用表 DDL）。 */
    String autoIncrementPk();

    /** 单条语句最多多少行（防止撞包体上限）。 */
    int maxRowsPerStatement();

    /** 逗号分隔的占位符组：{@code (?,?,?)}。 */
    static String placeholders(int columnCount) {
        return "(" + "?,".repeat(Math.max(0, columnCount - 1)) + "?)";
    }

    static String joinQuoted(List<String> names, SqlDialect dialect) {
        return names.stream().map(dialect::quote).reduce((a, b) -> a + "," + b).orElse("");
    }

    /** 引用单个标识符。 */
    String quote(String identifier);
}
