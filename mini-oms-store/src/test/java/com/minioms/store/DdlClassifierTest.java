package com.minioms.store;

import com.minioms.store.DdlClassifier.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DDL 分类：老实现把 QUERY 事件整类忽略，DDL 丢了也没人知道。
 * 这里把「哪些能自动重放、哪些必须叫人」钉成契约。
 */
class DdlClassifierTest {

    @Test
    void transactionControlIsIgnored() {
        for (String sql : new String[]{"BEGIN", "COMMIT", "ROLLBACK", "SET @@SESSION.GTID_NEXT= 'AUTOMATIC'"}) {
            assertEquals(Kind.IGNORE, DdlClassifier.classify(sql).kind(), sql);
        }
    }

    @Test
    void addColumnIsWhitelisted() {
        var r = DdlClassifier.classify("ALTER TABLE `shop`.`orders` ADD COLUMN `memo` VARCHAR(64) NULL");
        assertEquals(Kind.ADD_COLUMN, r.kind());
        assertEquals("orders", r.table());
        assertTrue(r.autoApplicable());
        assertFalse(r.needsHuman());

        assertEquals(Kind.ADD_COLUMN, DdlClassifier.classify("ALTER TABLE orders ADD amount DECIMAL(10,2)").kind());
        assertEquals(Kind.ADD_COLUMN,
                DdlClassifier.classify("alter table orders add column if not exists memo varchar(8)").kind());
    }

    @Test
    void destructiveAlterNeedsHuman() {
        for (String sql : new String[]{
                "ALTER TABLE orders DROP COLUMN memo",
                "ALTER TABLE orders MODIFY COLUMN amount BIGINT",
                "ALTER TABLE orders CHANGE COLUMN amount amt BIGINT",
                "ALTER TABLE orders ADD COLUMN a INT, DROP COLUMN b",
                "ALTER TABLE orders RENAME TO orders_old",
                "ALTER TABLE orders ADD INDEX idx_name (name)"}) {
            var r = DdlClassifier.classify(sql);
            assertEquals(Kind.OTHER_DDL, r.kind(), sql);
            assertTrue(r.needsHuman(), sql);
            assertEquals("orders", r.table(), sql);
        }
    }

    @Test
    void createDropTruncateNeedHuman() {
        assertEquals(Kind.OTHER_DDL, DdlClassifier.classify(
                "CREATE TABLE IF NOT EXISTS `shop`.`audit` (id BIGINT PRIMARY KEY)").kind());
        assertEquals("audit", DdlClassifier.classify("CREATE TABLE IF NOT EXISTS audit (id BIGINT)").table());
        assertEquals("orders", DdlClassifier.classify("DROP TABLE `shop`.`orders`").table());
        assertEquals(Kind.OTHER_DDL, DdlClassifier.classify("TRUNCATE TABLE orders").kind());
    }

    @Test
    void nonDdlStatementNeedsHuman() {
        var grant = DdlClassifier.classify("GRANT SELECT ON shop.* TO 'u'@'%'");
        assertEquals(Kind.OTHER_STATEMENT, grant.kind());
        assertTrue(grant.needsHuman());
        assertNull(grant.table());

        assertEquals(Kind.OTHER_DDL, DdlClassifier.classify("COMMENT ON TABLE orders IS 'x'").kind());
    }
}
