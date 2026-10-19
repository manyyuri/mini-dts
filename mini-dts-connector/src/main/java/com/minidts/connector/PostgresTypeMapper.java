package com.minidts.connector;

import com.minidts.common.ColumnType;
import com.minidts.common.TypeFamily;

import java.util.ArrayList;
import java.util.List;

/**
 * MySQL → PostgreSQL 的类型映射与不兼容项。
 *
 * 这张表就是「迁移评估」的核心：**能映射的给目标类型，不能映射的给出人话原因**。
 * 每条 problem 都不是学术洁癖，而是会在运行期变成数据错误或 CRC 假差异的东西。
 */
public final class PostgresTypeMapper implements TypeMapper {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String targetType(ColumnType source) {
        return switch (source.family()) {
            case INTEGER -> source.unsigned() ? "bigint" : "bigint";   // MySQL int/bigint 一律进 int8
            case DECIMAL -> "numeric";
            case STRING -> "varchar";
            case LONG_TEXT -> "text";
            case BOOLEAN -> "boolean";
            case BINARY -> "bytea";
            case DATETIME -> "timestamp";
            case DATE -> "date";
            case TIME -> "time";
            case JSON -> "jsonb";
            case ENUM_SET, UNKNOWN -> null;
        };
    }

    @Override
    public List<String> problems(ColumnType source) {
        List<String> out = new ArrayList<>();
        switch (source.family()) {
            case INTEGER -> {
                if (source.unsigned()) {
                    out.add("源列无符号（" + source.display() + "）：PG 没有 unsigned，"
                            + "取值域会变；建议改 bigint 并在业务侧确认不会写入负数");
                }
            }
            case DATETIME -> {
                if (source.raw().equals("timestamp")) {
                    out.add("MySQL timestamp 带时区语义（随会话时区换算），PG timestamp 不带；"
                            + "两边时区不一致会导致值偏移，需确认后选 timestamptz 或 timestamp");
                }
            }
            case JSON -> out.add("PG jsonb 会重排键、规范化数字（1.0 → 1.0/1），"
                    + "与源端 JSON 文本字节不同：写入没问题，但 CRC 校验会报假差异，需在比较层按 JSON 规范化");
            case ENUM_SET -> out.add("PG 没有 enum/set 列类型；需建独立类型或降级为 varchar，"
                    + "且取值范围校验从数据库层移到业务层");
            case UNKNOWN -> out.add("未知类型，无法自动映射：" + source.display());
            default -> {
            }
        }
        return out;
    }
}
