package com.minioms.cm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务租约：多节点部署时「谁跑这个任务」的唯一凭据。
 *
 * 老的实现只有一个进程内 synchronized —— 两个实例同时 start 同一任务会双跑。
 */
class TaskLeaseManagerTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource(new org.h2.Driver(),
                "jdbc:h2:mem:lease-" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        SchemaInit.init(jdbc);
    }

    private TaskLeaseManager node(String id, Duration ttl) {
        return new TaskLeaseManager(jdbc, id, ttl);
    }

    @Test
    void onlyOneNodeCanHoldALease() {
        TaskLeaseManager a = node("node-a", Duration.ofSeconds(30));
        TaskLeaseManager b = node("node-b", Duration.ofSeconds(30));

        assertTrue(a.acquire("t1"));
        assertFalse(b.acquire("t1"), "同一任务不能被两个节点同时持有");
        assertEquals("node-a", b.owner("t1").orElseThrow());

        assertTrue(a.renew("t1"));
        assertFalse(b.renew("t1"), "非持有者续租必须失败");
    }

    @Test
    void expiredLeaseCanBeTakenOver() throws Exception {
        TaskLeaseManager a = node("node-a", Duration.ofMillis(50));
        TaskLeaseManager b = node("node-b", Duration.ofMillis(50));

        assertTrue(a.acquire("t1"));
        Thread.sleep(120);                                  // 等租约过期（原节点失联）

        assertTrue(b.acquire("t1"), "过期租约必须可以被接管");
        assertEquals("node-b", a.owner("t1").orElseThrow());
        assertFalse(a.renew("t1"), "被接管后原节点续租失败 → 必须停手");
        assertEquals(java.util.Set.of(), a.held());
    }

    @Test
    void releaseFreesTheTask() {
        TaskLeaseManager a = node("node-a", Duration.ofSeconds(30));
        TaskLeaseManager b = node("node-b", Duration.ofSeconds(30));

        assertTrue(a.acquire("t1"));
        a.release("t1");
        assertEquals(null, b.owner("t1").orElse(null));
        assertTrue(b.acquire("t1"));
    }

    @Test
    void renewAllReportsLostLeases() throws Exception {
        TaskLeaseManager a = node("node-a", Duration.ofMillis(50));
        TaskLeaseManager b = node("node-b", Duration.ofMillis(50));
        assertTrue(a.acquire("t1"));
        assertTrue(a.acquire("t2"));

        Thread.sleep(120);
        assertTrue(b.acquire("t1"));            // b 抢走 t1

        assertEquals(java.util.Set.of("t1"), a.renewAll(), "只有被抢走的那个算丢失");
        assertEquals(java.util.Set.of("t2"), a.held());
    }
}
