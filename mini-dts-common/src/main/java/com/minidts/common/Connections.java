package com.minidts.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 简单连接工具（demo 规模；生产换连接池）。 */
public final class Connections {

    private Connections() {}

    public static Connection open(DbEndpoint ep) {
        try {
            return DriverManager.getConnection(ep.url(), ep.user(), ep.password());
        } catch (SQLException e) {
            throw new DtsException("无法连接 " + maskUrl(ep.url()) + ": " + e.getMessage(), e);
        }
    }

    public static String maskUrl(String url) {
        return url == null ? "null" : url.replaceAll("password=[^&;]*", "password=***");
    }
}
