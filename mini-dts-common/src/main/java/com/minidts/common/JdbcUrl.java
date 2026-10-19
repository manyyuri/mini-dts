package com.minidts.common;

/**
 * jdbc url 解析（只看库名，不做连通性判断）。
 *
 * 为什么需要它：源端读数据用 {@code schema.table} 显式限定，目标端若靠连接 URL 隐式决定库，
 * 一旦 URL 库名与任务里声明的 schema 不一致，就会「建任务成功、跑起来写错库」。
 * 建任务时用本类做一次显式校验，把隐式假设变成显式契约。
 */
public final class JdbcUrl {

    private JdbcUrl() {
    }

    /** {@code jdbc:mysql://host:3306/shop?a=b} → {@code shop}；没有库名返回 null。 */
    public static String databaseOf(String url) {
        if (url == null || url.isBlank()) {
            throw new DtsException("jdbc url 不能为空");
        }
        String rest = url.startsWith("jdbc:") ? url.substring("jdbc:".length()) : url;
        int schemeEnd = rest.indexOf("://");
        if (schemeEnd < 0) {
            return null;   // h2:mem:xxx 这类非层级 url，库名由连接自身决定
        }
        String afterAuthority = rest.substring(schemeEnd + 3);
        int query = afterAuthority.indexOf('?');
        if (query >= 0) {
            afterAuthority = afterAuthority.substring(0, query);
        }
        int slash = afterAuthority.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String db = afterAuthority.substring(slash + 1);
        int semicolon = db.indexOf(';');
        if (semicolon >= 0) {
            db = db.substring(0, semicolon);
        }
        db = db.trim();
        return db.isEmpty() ? null : db;
    }

    /** 库名比对（MySQL 库名大小写敏感，但迁移场景容忍大小写写法差异）。 */
    public static boolean sameDatabase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
