package com.minioms.connector;

import com.minioms.common.ColumnType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移评估（类型兼容性）：这次改造的产出不是"能迁"，而是**能提前说出哪里迁不过去、为什么**。
 */
class TypeMappingTest {

    private final TypeMapper pg = new PostgresTypeMapper();
    private final TypeMapper mysql = new MySqlTypeMapper();

    @Test
    void mysqlToMysqlHasNoProblems() {
        assertTrue(mysql.problems(ColumnType.parse("bigint")).isEmpty());
        assertTrue(mysql.problems(ColumnType.parse("datetime")).isEmpty());
        assertEquals("int(10) unsigned", mysql.targetType(ColumnType.parse("int", "int(10) unsigned")));
    }

    @Test
    void unsignedIntegerIsFlaggedForPostgres() {
        List<String> problems = pg.problems(ColumnType.parse("int", "int(10) unsigned"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("无符号"), problems.get(0));
        assertEquals("bigint", pg.targetType(ColumnType.parse("int", "int(10) unsigned")));
    }

    @Test
    void mysqlTimestampHasTimezoneSemanticsAndIsFlagged() {
        assertTrue(pg.problems(ColumnType.parse("timestamp")).stream()
                .anyMatch(p -> p.contains("时区")), "timestamp 带会话时区语义，PG 不带");
        assertTrue(pg.problems(ColumnType.parse("datetime")).isEmpty(), "datetime 没有时区语义，可直接映射");
    }

    @Test
    void jsonbNormalizationIsFlaggedBecauseItBreaksCrc() {
        List<String> problems = pg.problems(ColumnType.parse("json"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("CRC")), "jsonb 重排键会制造假差异");
    }

    @Test
    void enumAndUnknownAreNotAutoMappable() {
        assertTrue(pg.problems(ColumnType.parse("enum")).stream().anyMatch(p -> p.contains("enum")));
        assertNull(pg.targetType(ColumnType.parse("enum")), "没有目标类型 = 必须人工处理");
        assertTrue(pg.problems(ColumnType.parse("geometry")).stream().anyMatch(p -> p.contains("未知类型")));
    }

    @Test
    void reportMentionsColumnAndReason() {
        Map<String, List<ColumnType>> cols = new LinkedHashMap<>();
        cols.put("orders", List.of(ColumnType.parse("bigint"), ColumnType.parse("int", "int(10) unsigned")));
        cols.put("items", List.of(ColumnType.parse("json")));

        List<String> problems = TypeCompatibility.problems(cols, pg);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("orders (int(10) unsigned)"), problems.get(0));
        assertTrue(problems.get(1).startsWith("items (json)"), problems.get(1));
        assertTrue(TypeCompatibility.problems(cols, mysql).isEmpty(), "同构目标不该报问题");
    }

    @Test
    void targetTypesOnlyWhenFullyMappable() {
        Map<String, List<ColumnType>> ok = new LinkedHashMap<>();
        ok.put("orders", List.of(ColumnType.parse("bigint"), ColumnType.parse("varchar")));
        assertEquals("bigint, varchar", TypeCompatibility.targetTypes(ok, pg).get("orders"));

        Map<String, List<ColumnType>> bad = new LinkedHashMap<>();
        bad.put("orders", List.of(ColumnType.parse("bigint"), ColumnType.parse("enum")));
        assertTrue(TypeCompatibility.targetTypes(bad, pg).isEmpty(), "有列映射不出来时不给整表建议");
    }

    @Test
    void mapperIsBoundToDialect() {
        assertTrue(TypeMappers.forDialect(new PostgresDialect()) instanceof PostgresTypeMapper);
        assertTrue(TypeMappers.forDialect(new MySqlDialect()) instanceof MySqlTypeMapper);
        assertTrue(TypeMappers.forDialect(new H2Dialect()) instanceof MySqlTypeMapper);
    }
}
