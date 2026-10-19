package com.minioms.cm;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 控制面元数据库方言。
 *
 * 存在的理由只有一个：让 CM 元数据表既能跑在 H2（本地/单机 demo），也能跑在共享 MySQL
 * （多实例部署的前提 —— 元数据、租约、断点都必须被所有节点看见）。旧实现把 CLOB 等
 * H2 专有写法硬编码在 DDL 里，元数据库根本搬不到 MySQL。
 */
public enum SqlDialect {

    H2, MYSQL;

    /** 长文本列类型。 */
    public String longText() {
        return this == MYSQL ? "LONGTEXT" : "CLOB";
    }

    public static SqlDialect detect(DataSource dataSource) {
        if (dataSource == null) {
            return H2;
        }
        try (Connection conn = dataSource.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName().toLowerCase();
            return product.contains("mysql") ? MYSQL : H2;
        } catch (SQLException e) {
            return H2;
        }
    }
}
