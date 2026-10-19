package com.minidts.common;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 红线 R1 · Canonical 序列化。
 * 源端与目标端共用同一个编码器；两端任何字段表示差异都会导致 CRC 假不一致，这个类是唯一防线。
 *
 * 规则：
 *  - 列按 ORDINAL_POSITION 排序（由调用方保证，读取顺序即元数据顺序）
 *  - 每列 = 类型标签 + ':' + 值；列间分隔符 \u0001
 *  - NULL → 哨兵 "\u0002NULL"
 *  - 数值一律 BigDecimal.toPlainString()（禁止科学计数法）
 *  - 时间保留纳秒（datetime(3) 不得截断）
 *  - 字节串 hex 小写
 */
public final class RowCodec {

    public static final String COL_SEP = "\u0001";
    public static final String NULL_SENTINEL = "\u0002NULL";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS");

    private RowCodec() {}

    /** 编码一行（不含 schema/table/op，只编码数据列）。 */
    public static String encode(List<String> columns, List<Object> rawValues) {
        if (columns.size() != rawValues.size()) {
            throw new IllegalArgumentException("columns/values size mismatch: "
                    + columns.size() + " vs " + rawValues.size());
        }
        StringBuilder sb = new StringBuilder(Math.max(32, columns.size() * 16));
        for (int i = 0; i < rawValues.size(); i++) {
            if (i > 0) sb.append(COL_SEP);
            sb.append(cell(Values.normalize(rawValues.get(i))));
        }
        return sb.toString();
    }

    public static String encode(RowRecord r) {
        return encode(r.columns(), r.values());
    }

    /** 编码单个值；内部再做一次 normalize，保证外部直接调用也不会漏归一。 */
    public static String cell(Object raw) {
        if (raw == null) return NULL_SENTINEL;
        Object v = Values.normalize(raw);
        if (v == null) return NULL_SENTINEL;
        if (v instanceof Long l) return "N:" + l;
        if (v instanceof Boolean b) return "N:" + (b ? 1 : 0);
        if (v instanceof BigDecimal bd) return "N:" + canonicalDecimal(bd);
        if (v instanceof String s) return "S:" + s;
        if (v instanceof byte[] bytes) return "B:" + HexFormat.of().formatHex(bytes);
        if (v instanceof LocalDateTime dt) return "D:" + padNanos(dt);
        if (v instanceof LocalDate d) return "D:" + d.toString();
        if (v instanceof LocalTime t) return "D:" + padNanosTime(t);
        throw new IllegalStateException("unencodable value type: " + v.getClass().getName());
    }

    /** 去尾零后再 toPlainString：{@code 1.00} → {@code 1}，{@code 100} → {@code 100}（不会变科学计数法）。 */
    private static String canonicalDecimal(BigDecimal bd) {
        BigDecimal stripped = bd.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    private static String padNanos(LocalDateTime dt) {
        // 固定宽度纳秒，避免 "2026-01-01T00:00:00" 与 "...00.000" 不同串
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                + String.format(".%09d", dt.getNano());
    }

    private static String padNanosTime(LocalTime t) {
        return String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond())
                + String.format(".%09d", t.getNano());
    }

    public static long crc32(String s) {
        CRC32 crc = new CRC32();
        crc.update(s.getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }
}
