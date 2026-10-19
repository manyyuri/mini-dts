package com.minidts.common;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;

/**
 * 把 JDBC / binlog 反序列化出来的各种值形态归一到固定类型集合，供 RowCodec 编码。
 * 归一后只可能出现：Long, BigDecimal, String, byte[], Boolean, LocalDate, LocalTime, LocalDateTime, null。
 */
public final class Values {

    private Values() {}

    public static Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof Byte || v instanceof Short || v instanceof Integer) return ((Number) v).longValue();
        if (v instanceof java.math.BigInteger) return ((java.math.BigInteger) v).longValueExact();
        if (v instanceof Float) return BigDecimal.valueOf((Float) v);
        if (v instanceof Double) return BigDecimal.valueOf((Double) v);
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        // 带时区的时间类型（PG timestamptz 等）：按 JVM 默认时区折算成本地时间。
        // 迁移不做时区换算；两边时区语义不同属于「类型兼容问题」，由建任务时的评估报告出来。
        if (v instanceof java.time.OffsetDateTime odt) {
            return odt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
        }
        if (v instanceof java.time.ZonedDateTime zdt) return zdt.toLocalDateTime();
        if (v instanceof java.time.Instant instant) {
            return LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
        }
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof Time t) return t.toLocalTime();
        if (v instanceof Date d) return LocalDateTime.ofInstant(d.toInstant(),
                java.time.ZoneOffset.systemDefault().normalized());
        if (v instanceof LocalDate || v instanceof LocalTime || v instanceof LocalDateTime
                || v instanceof Long || v instanceof BigDecimal || v instanceof String
                || v instanceof Boolean || v instanceof byte[]) {
            return v;
        }
        // 兜底：按字符串处理（未知驱动类型）
        return String.valueOf(v);
    }

    public static boolean isIntegral(Object v) {
        return v instanceof Long;
    }
}
