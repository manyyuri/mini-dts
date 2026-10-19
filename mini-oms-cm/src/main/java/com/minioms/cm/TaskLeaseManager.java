package com.minioms.cm;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 任务租约：一个任务同一时刻只允许一个节点跑。
 *
 * 语义：
 * - 抢租约 = 插入一行，或接管「已过期」/「本来就是自己的」那一行
 * - 心跳续租 = 刷新 renewed_at；续不上说明被别的节点接管了，本节点必须立刻停手
 * - 释放 = 删除自己那一行
 *
 * 这里刻意不用数据库行锁/存储过程：H2 与 MySQL 都能跑，靠 UPDATE 影响行数判胜负即可。
 */
public class TaskLeaseManager {

    private final JdbcTemplate jdbc;
    private final String nodeId;
    private final Duration ttl;
    private final Set<String> held = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TaskLeaseManager(JdbcTemplate jdbc, String nodeId, Duration ttl) {
        this.jdbc = jdbc;
        this.nodeId = nodeId;
        this.ttl = ttl;
    }

    public String nodeId() {
        return nodeId;
    }

    /** 抢租约（含接管过期租约）。 */
    public boolean acquire(String taskId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        Timestamp expiredBefore = new Timestamp(System.currentTimeMillis() - ttl.toMillis());
        int taken = jdbc.update(
                "UPDATE oms_task_lease SET owner = ?, renewed_at = ? "
                        + "WHERE task_id = ? AND (owner = ? OR renewed_at < ?)",
                nodeId, now, taskId, nodeId, expiredBefore);
        if (taken == 0) {
            try {
                jdbc.update("INSERT INTO oms_task_lease(task_id, owner, renewed_at) VALUES (?, ?, ?)",
                        taskId, nodeId, now);
            } catch (DataAccessException e) {
                return false;      // 主键冲突：别人已经持有
            }
        }
        held.add(taskId);
        return true;
    }

    /** 续租；返回 false 表示租约已丢（被接管），调用方必须停止该任务。 */
    public boolean renew(String taskId) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        int updated = jdbc.update(
                "UPDATE oms_task_lease SET renewed_at = ? WHERE task_id = ? AND owner = ?",
                now, taskId, nodeId);
        if (updated == 0) {
            held.remove(taskId);
            return false;
        }
        return true;
    }

    /** 批量续租，返回丢失的任务（调用方据此停掉这些任务的运行时）。 */
    public Set<String> renewAll() {
        Set<String> lost = new LinkedHashSet<>();
        for (String taskId : Set.copyOf(held)) {
            if (!renew(taskId)) {
                lost.add(taskId);
            }
        }
        return lost;
    }

    public void release(String taskId) {
        held.remove(taskId);
        jdbc.update("DELETE FROM oms_task_lease WHERE task_id = ? AND owner = ?", taskId, nodeId);
    }

    public Optional<String> owner(String taskId) {
        var rows = jdbc.queryForList("SELECT owner FROM oms_task_lease WHERE task_id = ?", String.class, taskId);
        return rows.stream().findFirst();
    }

    public Set<String> held() {
        return Set.copyOf(held);
    }

    public Duration ttl() {
        return ttl;
    }
}
