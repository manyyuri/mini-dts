package com.minioms.connector;

/** 按方言取类型映射器（新增目标库时两处都要登记：SqlDialectFactory + 这里）。 */
public final class TypeMappers {

    private TypeMappers() {
    }

    public static TypeMapper forDialect(SqlDialect dialect) {
        return switch (dialect.name()) {
            case "postgresql" -> new PostgresTypeMapper();
            case "mysql", "h2" -> new MySqlTypeMapper();
            default -> throw new IllegalStateException("没有为该方言登记类型映射器: " + dialect.name());
        };
    }
}
