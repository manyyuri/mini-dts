package com.minioms.store;

import com.minioms.common.Connections;
import com.minioms.common.DbEndpoint;
import com.minioms.common.OmsException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 源库 binlog 环境预检——不满足直接拒绝建任务，而不是半夜同步挂掉。
 * 必须满足：log_bin=ON, binlog_format=ROW, binlog_row_image=FULL, gtid_mode=ON,
 * 且账号要有 REPLICATION SLAVE / REPLICATION CLIENT 权限。
 *
 * 最后这条是血泪教训：没有 REPLICATION SLAVE 时，binlog 客户端会「连上就断」——
 * 不抛异常、不打日志，表现为无限重连 + 一条数据都同步不过去，排查成本极高。
 */
public final class MySqlEnvChecker {

    private MySqlEnvChecker() {}

    public static void check(DbEndpoint source) {
        List<String> problems = new ArrayList<>();
        try (Connection conn = Connections.open(source); Statement st = conn.createStatement()) {
            String format = var(st, "@@binlog_format");
            if (!"ROW".equalsIgnoreCase(format)) {
                problems.add("binlog_format=" + format + "（需要 ROW）");
            }
            String rowImage = var(st, "@@binlog_row_image");
            if (!"FULL".equalsIgnoreCase(rowImage)) {
                problems.add("binlog_row_image=" + rowImage + "（需要 FULL，否则 UPDATE/DELETE 缺前镜像列）");
            }
            String gtid = var(st, "@@gtid_mode");
            if (!"ON".equalsIgnoreCase(gtid)) {
                problems.add("gtid_mode=" + gtid + "（需要 ON，断点续传依赖 GTID）");
            }
            // 权限检查：没有复制权限，dump 请求会被服务端直接拒绝（客户端侧表现为静默断开）
            try (ResultSet rs = st.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                boolean slave = false;
                while (rs.next()) {
                    String grant = rs.getString(1).toUpperCase(java.util.Locale.ROOT);
                    if (grant.contains("REPLICATION SLAVE") || grant.contains("ALL PRIVILEGES")) {
                        slave = true;
                        break;
                    }
                }
                if (!slave) {
                    problems.add("账号缺少 REPLICATION SLAVE 权限（binlog 订阅会「连上就断」，且不报错）。"
                            + "请执行：GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '<user>'@'%'");
                }
            }
            String logBin = var(st, "@@log_bin");
            if (!"1".equals(logBin) && !"ON".equalsIgnoreCase(logBin)) {
                problems.add("log_bin=" + logBin + "（需要开启）");
            }
        } catch (SQLException e) {
            throw new OmsException("环境预检连接失败: " + e.getMessage(), e);
        }
        if (!problems.isEmpty()) {
            throw new OmsException("源库不满足增量同步前提:\n  - " + String.join("\n  - ", problems));
        }
    }

    private static String var(Statement st, String expr) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT " + expr)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
