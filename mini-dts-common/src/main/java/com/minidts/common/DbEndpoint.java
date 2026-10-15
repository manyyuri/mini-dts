package com.minidts.common;

/** 数据库端点（源/目标连接信息）。 */
public record DbEndpoint(String url, String user, String password) {

    public DbEndpoint {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("jdbc url is required");
    }
}
