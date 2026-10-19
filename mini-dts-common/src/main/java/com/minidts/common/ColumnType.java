package com.minidts.common;

import java.util.Locale;

/**
 * 一列的「类型事实」：原始类型名 + 等价类 + 是否无符号。
 *
 * @param raw      原始类型名（如 {@code tinyint}、{@code int8}）
 * @param detail   完整列定义（MySQL 的 {@code COLUMN_TYPE}，如 {@code int(10) unsigned}），可为空
 * @param family   等价类
 * @param unsigned 是否无符号（MySQL 才有；PostgreSQL 没有这个属性，正是常见不兼容点）
 */
public record ColumnType(String raw, String detail, TypeFamily family, boolean unsigned) {

    public static ColumnType parse(String dataType) {
        return parse(dataType, null);
    }

    public static ColumnType parse(String dataType, String columnType) {
        String raw = dataType == null ? "" : dataType.trim().toLowerCase(Locale.ROOT);
        String detail = columnType == null ? "" : columnType.trim().toLowerCase(Locale.ROOT);
        boolean unsigned = detail.contains("unsigned");
        TypeFamily family = familyOf(raw, detail);
        return new ColumnType(raw, detail.isEmpty() ? null : detail, family, unsigned);
    }

    private static TypeFamily familyOf(String raw, String detail) {
        return switch (raw) {
            case "tinyint", "int1" -> detail.contains("(1)") && !detail.contains("unsigned")
                    ? TypeFamily.BOOLEAN : TypeFamily.INTEGER;
            case "bool", "boolean" -> TypeFamily.BOOLEAN;
            case "smallint", "int2", "mediumint", "int", "integer", "int4", "bigint", "int8",
                 "serial", "bigserial", "year" -> TypeFamily.INTEGER;
            case "decimal", "numeric", "dec", "fixed", "float", "float4", "float8", "double", "real" ->
                    TypeFamily.DECIMAL;
            case "char", "bpchar", "varchar", "nvarchar", "character", "character varying", "string" ->
                    TypeFamily.STRING;
            case "text", "tinytext", "mediumtext", "longtext", "clob" -> TypeFamily.LONG_TEXT;
            case "binary", "varbinary", "blob", "tinyblob", "mediumblob", "longblob", "bytea", "bit" ->
                    TypeFamily.BINARY;
            case "date" -> TypeFamily.DATE;
            case "datetime", "timestamp", "timestamptz", "timestamp with time zone",
                 "timestamp without time zone" -> TypeFamily.DATETIME;
            case "time", "timetz", "time with time zone", "time without time zone" -> TypeFamily.TIME;
            case "json", "jsonb" -> TypeFamily.JSON;
            case "enum", "set" -> TypeFamily.ENUM_SET;
            default -> TypeFamily.UNKNOWN;
        };
    }

    /** 展示用：{@code int unsigned} / {@code varchar(64)}。 */
    public String display() {
        return detail != null ? detail : raw;
    }
}
