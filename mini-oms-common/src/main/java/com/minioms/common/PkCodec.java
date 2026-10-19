package com.minioms.common;

import java.math.BigDecimal;

/**
 * 主键值的字符串编解码（断点表里存字符串，恢复时按列类型还原成可比较对象）。
 * 支持：整数族 / DECIMAL / 字符串族。其它类型显式报错——不静默降级。
 */
public final class PkCodec {

    private PkCodec() {}

    public static String encode(Object pk) {
        Object v = Values.normalize(pk);
        return RowCodec.cell(v);
    }

    public static Object decode(String encoded, String jdbcTypeName) {
        if (encoded == null) return null;
        String t = jdbcTypeName == null ? "" : jdbcTypeName.toUpperCase();
        if (encoded.startsWith("N:")) {
            String raw = encoded.substring(2);
            if (t.contains("DECIMAL") || t.contains("NUMERIC")) return new BigDecimal(raw);
            return Long.parseLong(raw);
        }
        if (encoded.startsWith("S:")) return encoded.substring(2);
        throw new OmsException("无法解码主键断点值 '" + encoded + "' (类型 " + jdbcTypeName + ")");
    }

    /** 该主键类型是否被支持（创建任务时提前失败，而不是跑到一半）。 */
    public static boolean supported(String jdbcTypeName) {
        if (jdbcTypeName == null) return false;
        String t = jdbcTypeName.toUpperCase();
        return t.contains("INT") || t.contains("DECIMAL") || t.contains("NUMERIC")
                || t.contains("CHAR") || t.contains("TEXT");
    }
}
