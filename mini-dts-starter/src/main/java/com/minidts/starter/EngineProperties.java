package com.minidts.starter;

import com.minidts.store.BinlogStoreService;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 实例级配置：多节点部署时每个节点都要显式知道自己是谁、能跑几个任务、总共能打多少流量。
 *
 * 默认值面向单机 demo：节点名自动取 host:pid，全局速率不设上限（0 = 不限），并发 8。
 */
@ConfigurationProperties(prefix = "minidts")
public record EngineProperties(
        String nodeId,
        int maxConcurrentTasks,
        long globalRowsPerSecond,
        Duration leaseTtl,
        long leaseRenewIntervalMillis,
        Boolean recoverOnStartup,
        int maxReconnects,
        Duration reconnectInitialBackoff,
        Duration reconnectMaxBackoff,
        int maxBufferedRows) {

    public EngineProperties {
        nodeId = (nodeId == null || nodeId.isBlank()) ? defaultNodeId() : nodeId;
        maxConcurrentTasks = maxConcurrentTasks <= 0 ? 8 : maxConcurrentTasks;
        globalRowsPerSecond = globalRowsPerSecond <= 0 ? Long.MAX_VALUE / 4 : globalRowsPerSecond;
        leaseTtl = (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative()) ? Duration.ofSeconds(30) : leaseTtl;
        leaseRenewIntervalMillis = leaseRenewIntervalMillis <= 0 ? 10_000 : leaseRenewIntervalMillis;
        maxReconnects = maxReconnects <= 0 ? 10 : maxReconnects;
        reconnectInitialBackoff = reconnectInitialBackoff == null || reconnectInitialBackoff.isZero()
                ? Duration.ofMillis(500) : reconnectInitialBackoff;
        reconnectMaxBackoff = reconnectMaxBackoff == null || reconnectMaxBackoff.isZero()
                ? Duration.ofSeconds(10) : reconnectMaxBackoff;
        maxBufferedRows = maxBufferedRows <= 0 ? 20_000 : maxBufferedRows;
        recoverOnStartup = recoverOnStartup == null || recoverOnStartup;
    }

    public BinlogStoreService.Reconnect reconnect() {
        return new BinlogStoreService.Reconnect(
                maxReconnects, reconnectInitialBackoff.toMillis(), reconnectMaxBackoff.toMillis(), maxBufferedRows);
    }

    private static String defaultNodeId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "localhost";
        }
        return host + ":" + ProcessHandle.current().pid();
    }
}
