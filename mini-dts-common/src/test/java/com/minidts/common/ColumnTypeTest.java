package com.minidts.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 类型等价类：把不同库的类型词汇收敛成可比的东西（跨方言指纹与兼容评估的前提）。 */
class ColumnTypeTest {

    @Test
    void integerFamilyAcrossDialects() {
        assertEquals(TypeFamily.INTEGER, ColumnType.parse("bigint").family());
        assertEquals(TypeFamily.INTEGER, ColumnType.parse("int8").family(), "PG 的 int8 就是 MySQL 的 bigint");
        assertEquals(TypeFamily.INTEGER, ColumnType.parse("int4").family());
        assertEquals(TypeFamily.INTEGER, ColumnType.parse("INT").family(), "大小写不敏感");
    }

    @Test
    void tinyintOneIsBooleanButTinyintTwoIsInteger() {
        assertEquals(TypeFamily.BOOLEAN, ColumnType.parse("tinyint", "tinyint(1)").family());
        assertEquals(TypeFamily.INTEGER, ColumnType.parse("tinyint", "tinyint(4)").family());
        assertEquals(TypeFamily.BOOLEAN, ColumnType.parse("boolean").family());
        assertEquals(TypeFamily.BOOLEAN, ColumnType.parse("bool").family());
    }

    @Test
    void unsignedIsRecorded() {
        ColumnType t = ColumnType.parse("int", "int(10) unsigned");
        assertEquals(TypeFamily.INTEGER, t.family());
        assertTrue(t.unsigned(), "unsigned 是 MySQL 独有属性，是跨方言最常见的坑");
        assertFalse(ColumnType.parse("int").unsigned());
    }

    @Test
    void stringAndLongTextAreDifferentFamilies() {
        assertEquals(TypeFamily.STRING, ColumnType.parse("varchar").family());
        assertEquals(TypeFamily.LONG_TEXT, ColumnType.parse("text").family());
        assertEquals(TypeFamily.LONG_TEXT, ColumnType.parse("clob").family());
        assertEquals(TypeFamily.BINARY, ColumnType.parse("bytea").family());
        assertEquals(TypeFamily.JSON, ColumnType.parse("jsonb").family());
    }

    @Test
    void timeFamiliesAndUnknown() {
        assertEquals(TypeFamily.DATETIME, ColumnType.parse("datetime").family());
        assertEquals(TypeFamily.DATETIME, ColumnType.parse("timestamptz").family());
        assertEquals(TypeFamily.TIME, ColumnType.parse("time").family());
        assertEquals(TypeFamily.UNKNOWN, ColumnType.parse("geometry").family());
    }
}
