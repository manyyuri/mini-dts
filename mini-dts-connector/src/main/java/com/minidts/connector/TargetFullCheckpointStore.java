package com.minidts.connector;

import com.minidts.common.FullCheckpointStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * 全量分片断点（存在目标库 dts_full_checkpoint）。
 *
 * 为什么放目标库而不是控制面本地库：断点和它保护的数据在同一个库，
 * 一边是「数据已提交、断点未落」只能重做（幂等收敛），另一边是「换了节点断点就丢」，
 * 后者会让迁移从头再来一遍。放目标库后，任何节点接管都能接着跑。
 */
public final class TargetFullCheckpointStore implements FullCheckpointStore {

    private final Connection connection;
    private final Object lock = new Object();

    public TargetFullCheckpointStore(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void markChunkDone(String taskId, String upperPk) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dts_full_checkpoint(task_id, upper_pk, done_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                ps.setString(1, taskId);
                ps.setString(2, upperPk);
                ps.executeUpdate();
            } catch (SQLException e) {
                if (!isDuplicate(e)) {
                    throw new IllegalStateException("记录全量断点失败: " + e.getMessage(), e);
                }
                // 重复标记：并发重做同一分片时的正常情况
            }
        }
    }

    @Override
    public Set<String> completedUpperPks(String taskId) {
        Set<String> done = new HashSet<>();
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT upper_pk FROM dts_full_checkpoint WHERE task_id = ?")) {
                ps.setString(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        done.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("读取全量断点失败: " + e.getMessage(), e);
            }
        }
        return done;
    }

    public void clear(String taskId) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM dts_full_checkpoint WHERE task_id = ?")) {
                ps.setString(1, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("清理全量断点失败: " + e.getMessage(), e);
            }
        }
    }

    private static boolean isDuplicate(SQLException e) {
        return e.getSQLState() != null && e.getSQLState().startsWith("23");
    }
}
