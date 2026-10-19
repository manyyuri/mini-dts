package com.minioms.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GtidStateTest {

    @Test
    void parseAndSerialize() {
        GtidState s = GtidState.of("3e11fa47-71ca-11e1-9e33-c80aa9429562:1-5");
        s.add("3e11fa47-71ca-11e1-9e33-c80aa9429562", 9);
        assertEquals("3e11fa47-71ca-11e1-9e33-c80aa9429562:1-9", s.serialize());
    }

    @Test
    void mergeKeepsMax() {
        GtidState a = GtidState.of("s1:1-10");
        GtidState b = GtidState.of("s1:1-4,s2:1-2");
        a.mergeFrom(b);
        assertEquals("s1:1-10,s2:1-2", a.serialize());
    }

    @Test
    void emptyIsSafe() {
        assertTrue(GtidState.of(null).isEmpty());
        assertTrue(GtidState.of("").isEmpty());
        assertEquals("", GtidState.of("  ").serialize());
    }

    @Test
    void singleGtidFormAccepted() {
        assertEquals("s:1-3", GtidState.of("s:3").serialize());
    }
}
