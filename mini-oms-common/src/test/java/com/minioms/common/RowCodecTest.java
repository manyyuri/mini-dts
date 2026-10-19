package com.minioms.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RowCodecTest {

    @Test
    void nullUsesSentinel() {
        assertEquals("\u0002NULL", RowCodec.encode(List.of("a"), Arrays.asList((Object) null)));
    }

    @Test
    void decimalNeverScientificNotation() {
        assertEquals("N:0.000000001", RowCodec.encode(List.of("a"), List.of(new BigDecimal("1E-9"))));
    }

    @Test
    void datetimeKeepsMillis() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 12, 30, 0, 123_000_000);
        assertEquals("D:2026-01-01T12:30:00.123000000", RowCodec.encode(List.of("t"), List.of(t)));
    }

    @Test
    void nanosNotTruncated() {
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 1);
        String enc = RowCodec.encode(List.of("t"), List.of(t));
        assertEquals("D:2026-01-01T00:00:00.000000001", enc);
    }

    @Test
    void bytesHexLowercase() {
        assertEquals("B:0a0bff", RowCodec.encode(List.of("b"), List.of(new byte[]{10, 11, (byte) 0xff})));
    }

    @Test
    void sqlTimestampNormalized() {
        java.sql.Timestamp ts = java.sql.Timestamp.valueOf("2026-03-04 05:06:07.89");
        assertEquals("D:2026-03-04T05:06:07.890000000", RowCodec.encode(List.of("t"), List.of(ts)));
    }

    @Test
    void columnSeparatorCannotCollide() {
        // 含分隔符与哨兵的字符串值也不会被误读为结构
        String evil = RowCodec.COL_SEP + RowCodec.NULL_SENTINEL + "x";
        String enc = RowCodec.encode(List.of("a"), List.of(evil));
        assertEquals("S:" + evil, enc);
    }

    @Test
    void sameRowDifferentDoubleVsBigDecimalNormalizesEqual() {
        String viaDouble = RowCodec.encode(List.of("x"), List.of((Object) 0.1d));
        String viaDecimal = RowCodec.encode(List.of("x"), List.of(new BigDecimal("0.1")));
        assertEquals(viaDecimal, viaDouble);
    }

    @Test
    void crc32Stable() {
        assertEquals(RowCodec.crc32("abc"), RowCodec.crc32("abc"));
        assertNotEquals(RowCodec.crc32("abc"), RowCodec.crc32("abd"));
    }

    @Test
    void intWidenedToLong() {
        assertEquals("N:7", RowCodec.encode(List.of("a"), List.of(7)));
    }
}
