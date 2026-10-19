package com.minioms.common;

/**
 * 类型等价类：把不同数据库的类型词汇收敛成少数几族。
 *
 * 为什么需要它：跨方言比较（指纹、兼容性检查、CRC）不能比「类型名」，
 * 只能比「这一列承载什么语义」。`BIGINT` 与 `int8` 是同一族，`VARCHAR` 与 `text` 不是。
 */
public enum TypeFamily {
    INTEGER,
    DECIMAL,
    STRING,
    LONG_TEXT,
    BOOLEAN,
    BINARY,
    DATETIME,
    DATE,
    TIME,
    JSON,
    ENUM_SET,
    UNKNOWN;

    public boolean isNumeric() {
        return this == INTEGER || this == DECIMAL;
    }
}
