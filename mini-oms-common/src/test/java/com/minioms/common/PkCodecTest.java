package com.minioms.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PkCodecTest {

    @Test
    void longRoundTrip() {
        Object decoded = PkCodec.decode(PkCodec.encode(12345L), "BIGINT");
        assertEquals(12345L, ((Long) decoded).longValue());
    }

    @Test
    void decimalRoundTrip() {
        Object decoded = PkCodec.decode(PkCodec.encode(new java.math.BigDecimal("99.5")), "DECIMAL(10,2)");
        assertEquals(new java.math.BigDecimal("99.5"), decoded);
    }

    @Test
    void stringRoundTrip() {
        Object decoded = PkCodec.decode(PkCodec.encode("abc-001"), "VARCHAR(32)");
        assertEquals("abc-001", decoded);
    }

    @Test
    void supportedTypes() {
        assertTrue(PkCodec.supported("BIGINT"));
        assertTrue(PkCodec.supported("varchar"));
        assertFalse(PkCodec.supported("DATE"));
        assertFalse(PkCodec.supported(null));
    }
}
