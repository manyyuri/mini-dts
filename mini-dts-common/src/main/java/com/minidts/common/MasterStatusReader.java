package com.minidts.common;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 一致性位点读取（红线 R3）：
 * 全量开始前记录 gtid_executed —— 增量从该位点起播，全量期间的任何写入都会被重放（幂等），不丢不漏。
 */
public final class MasterStatusReader {

    private MasterStatusReader() {}

    public static String readGtidExecuted(Connection source) {
        try (Statement st = source.createStatement(); ResultSet rs = st.executeQuery("SELECT @@GLOBAL.gtid_executed")) {
            if (!rs.next()) {
                throw new DtsException("无法读取 gtid_executed（源库响应异常）");
            }
            String gtid = rs.getString(1);
            if (gtid == null || gtid.isBlank()) {
                throw new DtsException("源库 gtid_executed 为空——请确认 gtid_mode=ON（见环境预检）");
            }
            return gtid;
        } catch (SQLException e) {
            throw new DtsException("读取一致性位点失败: " + e.getMessage(), e);
        }
    }
}
