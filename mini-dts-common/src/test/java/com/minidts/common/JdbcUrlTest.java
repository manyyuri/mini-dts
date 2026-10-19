package com.minidts.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0-3 的基础：库名必须能从 URL 里可靠解析出来，否则「写错库」校验形同虚设。 */
class JdbcUrlTest {

    @Test
    void parsesDatabaseFromMysqlUrl() {
        assertEquals("shop", JdbcUrl.databaseOf("jdbc:mysql://src:3306/shop"));
        assertEquals("shop", JdbcUrl.databaseOf("jdbc:mysql://src:3306/shop?useSSL=false&serverTimezone=UTC"));
        assertEquals("shop_2", JdbcUrl.databaseOf("jdbc:mysql://src/shop_2"));
    }

    @Test
    void returnsNullWhenNoDatabase() {
        assertNull(JdbcUrl.databaseOf("jdbc:mysql://src:3306"));
        assertNull(JdbcUrl.databaseOf("jdbc:mysql://src:3306/"));
        assertNull(JdbcUrl.databaseOf("jdbc:h2:mem:test;MODE=MySQL"));
    }

    @Test
    void comparesCaseInsensitively() {
        assertTrue(JdbcUrl.sameDatabase("shop", "SHOP"));
        assertFalse(JdbcUrl.sameDatabase("shop", "shop_bak"));
        assertFalse(JdbcUrl.sameDatabase(null, "shop"));
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(DtsException.class, () -> JdbcUrl.databaseOf(" "));
    }
}
