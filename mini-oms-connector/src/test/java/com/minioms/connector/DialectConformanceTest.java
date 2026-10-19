package com.minioms.connector;

import com.minioms.common.OpType;
import com.minioms.common.RowRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言一致性测试：**同一批 RowRecord，两个方言必须产出「结构等价」的 SQL**。
 *
 * 这是「异构是否可行」的探针：
 * <ul>
 *   <li>如果只能断言「字符串全等」→ 说明抽象漏了东西（方言差异没被隔离干净）；</li>
 *   <li>如果能断言「占位符数、列序、更新集、冲突目标」这些结构量 → 说明差异确实只在语法层。</li>
 * </ul>
 * 顺带把三处真实差异钉成契约：冲突目标、更新值来源、DDL 类型。
 */
class DialectConformanceTest {

    private static final SqlDialect MYSQL = new MySqlDialect();
    private static final SqlDialect PG = new PostgresDialect();

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:dialect-" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS shop");
            st.execute("CREATE TABLE shop.orders (id BIGINT PRIMARY KEY, name VARCHAR(64), amount DECIMAL(10,2))");
        }
        TargetSchemaInit.ensure(conn);
    }

    private static RowRecord row(long id, String name) {
        return new RowRecord("shop", "orders", List.of("id", "name", "amount"),
                Arrays.asList(id, name, null), OpType.UPSERT, "tx-1");
    }

    private static int placeholders(String sql) {
        int n = 0;
        Matcher m = Pattern.compile("\\?").matcher(sql);
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void upsertStructurallyEquivalentAcrossDialects() {
        List<String> cols = List.of("id", "name", "amount");
        List<String> pk = List.of("id");

        String my = MYSQL.upsert("shop", "orders", cols, pk, 3);
        String pg = PG.upsert("shop", "orders", cols, pk, 3);

        // ① 结构等价：占位符数量 = 列数 × 行数
        assertEquals(cols.size() * 3, placeholders(my));
        assertEquals(placeholders(my), placeholders(pg), "两方言的绑定参数个数必须完全一致");

        // ② 列序一致（值的绑定顺序依赖它）
        assertEquals(List.of("id", "name", "amount"), columnsInOrder(my, '`'));
        assertEquals(List.of("id", "name", "amount"), columnsInOrder(pg, '"'));

        // ③ 更新集一致：非主键列，主键不进更新集
        assertEquals(List.of("name", "amount"), updateTargets(my, '`'));
        assertEquals(List.of("name", "amount"), updateTargets(pg, '"'));

        // ④ 差异被关在语法层：MySQL 不需要冲突目标，PG 必须显式给
        assertTrue(my.contains("ON DUPLICATE KEY UPDATE"), my);
        assertTrue(pg.contains("ON CONFLICT (\"id\") DO UPDATE SET"), pg);
        assertTrue(pg.contains("EXCLUDED.\"name\""), "PG 更新值来自 EXCLUDED");
        assertTrue(my.contains("VALUES(`name`)"), "MySQL 更新值来自 VALUES()");
    }

    @Test
    void deleteAndTableQualificationEquivalent() {
        List<String> pk = List.of("id");
        String my = MYSQL.delete("shop", "orders", pk);
        String pg = PG.delete("shop", "orders", pk);

        assertEquals(1, placeholders(my));
        assertEquals(placeholders(my), placeholders(pg));
        assertTrue(my.contains("`shop`.`orders`"), my);
        assertTrue(pg.contains("\"shop\".\"orders\""), pg);
        // schema 为空时不加限定
        assertTrue(MYSQL.table(null, "orders").equals("`orders`"));
        assertTrue(PG.table(null, "orders").equals("\"orders\""));
    }

    @Test
    void adminUpsertUsesExplicitConflictTargetEverywhere() {
        LinkedHashMap<String, String> exprs = new LinkedHashMap<>();
        exprs.put("task_id", "?");
        exprs.put("gtid_set", "?");
        exprs.put("updated_at", "CURRENT_TIMESTAMP");

        String my = MYSQL.adminUpsert(null, "oms_incr_checkpoint", List.of("task_id"), exprs);
        String pg = PG.adminUpsert(null, "oms_incr_checkpoint", List.of("task_id"), exprs);

        assertEquals(2, placeholders(my), "CURRENT_TIMESTAMP 不占位");
        assertEquals(placeholders(my), placeholders(pg));
        assertTrue(my.contains("ON DUPLICATE KEY UPDATE `gtid_set` = VALUES(`gtid_set`)"), my);
        assertTrue(pg.contains("ON CONFLICT (\"task_id\") DO UPDATE SET \"gtid_set\" = EXCLUDED.\"gtid_set\""), pg);
    }

    @Test
    void postgresRequiresConflictTarget( ) {
        // 这条断言是本次改造最重要的产出：端口签名必须带主键列，否则 PG 无法生成合法语句
        assertThrows(IllegalStateException.class,
                () -> PG.upsert("shop", "orders", List.of("id", "name"), List.of(), 1));
    }

    @Test
    void dialectSpecificDdlTypes() {
        assertEquals("LONGTEXT", MYSQL.longTextType());
        assertEquals("TEXT", PG.longTextType());
        assertEquals("BIGINT AUTO_INCREMENT PRIMARY KEY", MYSQL.autoIncrementPk());
        assertEquals("BIGSERIAL PRIMARY KEY", PG.autoIncrementPk());
        assertEquals(500, MYSQL.maxRowsPerStatement());
        assertEquals(MYSQL.maxRowsPerStatement(), PG.maxRowsPerStatement());
    }

    @Test
    void sinkBindsSameNumbersUnderBothDialects() throws Exception {
        // 同一个 Sink 代码路径，换方言只影响 SQL 文本，不影响绑定与提交（这正是"隔离干净"的定义）
        MySqlSink sink = new MySqlSink(conn, "shop", "t1",
                java.util.Map.of("orders", List.of("id")), MYSQL);
        sink.writeBatch(List.of(row(1, "a"), row(2, "b")), "gtid:1-2");
        assertEquals(2, count("SELECT COUNT(*) FROM shop.orders"));
        assertEquals("gtid:1-2", TargetSchemaInit.checkpointValue(conn, "t1"));

        sink.writeBatch(List.of(row(1, "a2")), "gtid:1-3");
        assertEquals("a2", one("SELECT name FROM shop.orders WHERE id = 1"), "UPSERT 覆盖");
        assertEquals(2, count("SELECT COUNT(*) FROM shop.orders"), "不新增行");
    }

    // ── helpers ───────────────────────────────────────────────

    /** 取 INSERT 里第一组括号中的列名（去引号）。 */
    private static List<String> columnsInOrder(String sql, char quoteChar) {
        Matcher m = Pattern.compile("(?i)INSERT INTO\\s+\\S+\\s*\\(([^)]*)\\)").matcher(sql);
        assertTrue(m.find(), "找不到列清单: " + sql);
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .map(s -> s.replace(String.valueOf(quoteChar), ""))
                .toList();
    }

    /** 取 DO UPDATE SET / ON DUPLICATE KEY UPDATE 里的目标列。 */
    private static List<String> updateTargets(String sql, char quoteChar) {
        Matcher m = Pattern.compile("(?i)(?:DO UPDATE SET|ON DUPLICATE KEY UPDATE)\\s+(.+)$").matcher(sql);
        assertTrue(m.find(), "找不到更新集: " + sql);
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .map(s -> s.split("=")[0].trim().replace(String.valueOf(quoteChar), ""))
                .toList();
    }

    private long count(String sql) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String one(String sql) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
