package com.minidts.store;

import com.minidts.common.Connections;
import com.minidts.common.DbEndpoint;
import com.minidts.common.DtsException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 源库 binlog 环境预检——不满足直接拒绝建任务，而不是半夜同步挂掉。
 * 必须满足：log_bin=ON, binlog_format=ROW, binlog_row_image=FULL, gtid_mode=ON。
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
            String logBin = var(st, "@@log_bin");
            if (!"1".equals(logBin) && !"ON".equalsIgnoreCase(logBin)) {
                problems.add("log_bin=" + logBin + "（需要开启）");
            }
        } catch (SQLException e) {
            throw new DtsException("环境预检连接失败: " + e.getMessage(), e);
        }
        if (!problems.isEmpty()) {
            throw new DtsException("源库不满足增量同步前提:\n  - " + String.join("\n  - ", problems));
        }
    }

    private static String var(Statement st, String expr) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT " + expr)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
