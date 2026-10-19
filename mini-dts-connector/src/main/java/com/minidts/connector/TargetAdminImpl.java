package com.minidts.connector;

import com.minidts.common.DbEndpoint;
import com.minidts.common.DdlEvent;
import com.minidts.common.DtsException;
import com.minidts.common.TargetAdmin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标端管理端口实现：DDL 执行 / DDL 台账 / 结构指纹。
 *
 * 用独立连接（不是 Sink 的连接）：DDL 会隐式提交，绝不能混进「数据 + 位点同事务」的那条链路。
 */
public class TargetAdminImpl implements TargetAdmin {

    /** 匹配 DDL 里的第一个表引用（带不带反引号都行）。 */
    private static final Pattern TABLE_REF = Pattern.compile("(?i)\\b(TABLE\\s+)(`?)([A-Za-z0-9_$]+)\\2");

    private final DbEndpoint target;
    private final Connection connection;

    private TargetAdminImpl(DbEndpoint target, Connection connection) {
        this.target = target;
        this.connection = connection;
    }

    public static TargetAdminImpl open(DbEndpoint target) {
        try {
            Connection conn = DriverManager.getConnection(target.url(), target.user(), target.password());
            TargetSchemaInit.ensure(conn);
            return new TargetAdminImpl(target, conn);
        } catch (SQLException e) {
            throw new DtsException("打开目标管理连接失败 " + target.url() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void applyDdl(String schema, String sql) {
        String ddl = schema == null || schema.isBlank() ? sql : qualify(schema, sql);
        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new DtsException("目标端执行 DDL 失败: " + e.getMessage() + " | " + ddl, e);
        }
    }

    @Override
    public long journal(DdlEvent event) {
        try {
            TargetSchemaInit.insertDdlEvent(connection, event);
            try (PreparedStatement ps = connection.prepareStatement("SELECT MAX(id) FROM dts_ddl_event")) {
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        } catch (SQLException e) {
            throw new DtsException("记录 DDL 台账失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> fingerprints(String taskId) {
        Map<String, String> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT table_name, fingerprint FROM dts_schema_fingerprint WHERE task_id = ?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new DtsException("读取结构指纹失败: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public void putFingerprints(String taskId, Map<String, String> fingerprintsByTable) {
        SqlDialect dialect = SqlDialectFactory.detect(connection);
        LinkedHashMap<String, String> exprs = new LinkedHashMap<>();
        exprs.put("task_id", "?");
        exprs.put("table_name", "?");
        exprs.put("fingerprint", "?");
        exprs.put("updated_at", "CURRENT_TIMESTAMP");
        String sql = dialect.adminUpsert(null, "dts_schema_fingerprint",
                List.of("task_id", "table_name"), exprs);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (Map.Entry<String, String> e : fingerprintsByTable.entrySet()) {
                ps.setString(1, taskId);
                ps.setString(2, e.getKey());
                ps.setString(3, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new DtsException("登记结构指纹失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public DbEndpoint endpoint() {
        return target;
    }

    /** 只在 DDL 语句里补库名前缀：`ALTER TABLE orders ...` → `ALTER TABLE `shop`.`orders` ...`。 */
    private String qualify(String schema, String sql) {
        Matcher m = TABLE_REF.matcher(sql);
        if (!m.find()) {
            return sql;
        }
        SqlDialect dialect = SqlDialectFactory.detect(connection);
        String replacement = m.group(1) + dialect.quote(schema) + "." + dialect.quote(m.group(3));
        return sql.substring(0, m.start()) + replacement + sql.substring(m.end());
    }
}
