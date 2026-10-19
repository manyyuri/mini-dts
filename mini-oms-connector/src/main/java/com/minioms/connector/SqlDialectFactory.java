package com.minioms.connector;

import com.minioms.common.OmsException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** 按连接的产品名探测方言。新增目标库时只在这里登记一次。 */
public final class SqlDialectFactory {

    private SqlDialectFactory() {
    }

    public static SqlDialect detect(Connection connection) {
        String product;
        try {
            product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        } catch (SQLException e) {
            throw new OmsException("读取目标库元数据失败: " + e.getMessage(), e);
        }
        if (product.contains("mysql") || product.contains("mariadb")) {
            return new MySqlDialect();
        }
        if (product.contains("postgres")) {
            return new PostgresDialect();
        }
        if (product.contains("h2")) {
            return new H2Dialect();
        }
        throw new OmsException("暂不支持的目标库类型: " + product
                + "（已实现 mysql / postgresql / h2；新增目标库请实现 SqlDialect 并在此登记）");
    }
}
