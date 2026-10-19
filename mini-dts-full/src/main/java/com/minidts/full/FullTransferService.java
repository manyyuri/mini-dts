package com.minidts.full;

import com.minidts.common.Chunk;
import com.minidts.common.Connections;
import com.minidts.common.DbEndpoint;
import com.minidts.common.DtsException;
import com.minidts.common.FullCheckpointStore;
import com.minidts.common.JdbcPkBoundaryScanner;
import com.minidts.common.OpType;
import com.minidts.common.PkCodec;
import com.minidts.common.PkResolver;
import com.minidts.common.RateLimiter;
import com.minidts.common.TokenBucket;
import com.minidts.common.RowRecord;
import com.minidts.common.TaskStateNotifier;
import com.minidts.connector.Sink;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全量迁移：主键分片 + chunk 断点续传。
 *
 * - 键集分页（禁止 LIMIT offset）
 * - 先写目标库数据成功，再落断点（红线：顺序不可反）
 * - 分片级并行 + 令牌桶限速
 * - 恢复：跳过已完成分片；边界漂移只会多干重活（幂等），不会漏数据——
 *   一致性位点之后的变更由 Store 重放兜底（红线 R3）
 */
public class FullTransferService {

    private final String taskId;
    private final DbEndpoint source;
    private final String schema;
    private final String table;
    private final int chunkSize;
    private final int parallelism;
    private final RateLimiter limiter;
    private final FullCheckpointStore checkpointStore;
    private final SinkFactory sinkFactory;
    private final TaskStateNotifier notifier;

    private volatile boolean stopped = false;

    public final AtomicLong rowsCopied = new AtomicLong();
    public final AtomicLong chunksDone = new AtomicLong();
    public final AtomicLong chunksSkipped = new AtomicLong();

    /** 每个 worker 独立连接/独立 Sink（连接与事务不可跨线程共享）。 */
    @FunctionalInterface
    public interface SinkFactory {
        Sink open() throws SQLException;
    }

    public FullTransferService(String taskId, DbEndpoint source, String schema, String table,
                               int chunkSize, int parallelism, long rowsPerSecond,
                               FullCheckpointStore checkpointStore, SinkFactory sinkFactory,
                               TaskStateNotifier notifier) {
        this(taskId, source, schema, table, chunkSize, parallelism,
                new TokenBucket(rowsPerSecond), checkpointStore, sinkFactory, notifier);
    }

    /** 外部注入限流器（实例级全局预算 + 任务级预算由 starter 组合）。 */
    public FullTransferService(String taskId, DbEndpoint source, String schema, String table,
                               int chunkSize, int parallelism, RateLimiter limiter,
                               FullCheckpointStore checkpointStore, SinkFactory sinkFactory,
                               TaskStateNotifier notifier) {
        this.taskId = taskId;
        this.source = source;
        this.schema = schema;
        this.table = table;
        this.chunkSize = Math.max(1, chunkSize);
        this.parallelism = Math.max(1, parallelism);
        this.limiter = limiter;
        this.checkpointStore = checkpointStore;
        this.sinkFactory = sinkFactory;
        this.notifier = notifier;
    }

    public void stop() {
        this.stopped = true;
    }

    /** 同步执行（starter 里包线程）。 */
    public void run() {
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        String firstError = null;
        try (Connection metaConn = Connections.open(source)) {
            List<String> pks = PkResolver.resolvePkColumns(metaConn, schema, table);
            if (pks.size() > 1) {
                throw new DtsException("复合主键暂不支持（" + table + ": " + pks + "），任务拒绝启动");
            }
            String pk = pks.get(0);
            String pkType = PkResolver.pkTypeName(metaConn, schema, table);
            if (!PkCodec.supported(pkType)) {
                throw new DtsException("主键类型不支持: " + pkType + "（支持整数/DECIMAL/字符串）");
            }

            List<Object> uppers = JdbcPkBoundaryScanner.scanUpperBounds(metaConn, schema, table, pk, chunkSize);
            Set<String> completed = checkpointStore.completedUpperPks(taskId);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < uppers.size(); i++) {
                if (stopped) break;
                Object upper = uppers.get(i);
                String upperKey = PkCodec.encode(upper);
                if (completed.contains(upperKey)) {
                    chunksSkipped.incrementAndGet();
                    continue;
                }
                Object lower = i == 0 ? null : uppers.get(i - 1);
                Chunk chunk = new Chunk(i, lower, upper);
                futures.add(executor.submit(() -> copyChunk(chunk, pk, pkType)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    firstError = rootMessage(e);
                    executor.shutdownNow();
                    break;
                }
            }
        } catch (Exception e) {
            firstError = rootMessage(e);
        } finally {
            executor.shutdownNow();
        }
        if (firstError != null) {
            notifier.onError(taskId, "全量失败: " + firstError);
        } else if (!stopped) {
            notifier.onFullDone(taskId);
        }
    }

    private void copyChunk(Chunk chunk, String pk, String pkType) {
        if (stopped) return;
        String qualified = JdbcPkBoundaryScanner.qualify(schema, table);
        try (Connection src = Connections.open(source); Sink sink = sinkFactory.open()) {
            String sql = chunk.lowerExclusive() == null
                    ? "SELECT * FROM " + qualified + " WHERE " + JdbcPkBoundaryScanner.quote(pk) + " <= ? ORDER BY " + JdbcPkBoundaryScanner.quote(pk)
                    : "SELECT * FROM " + qualified + " WHERE " + JdbcPkBoundaryScanner.quote(pk) + " > ? AND "
                      + JdbcPkBoundaryScanner.quote(pk) + " <= ? ORDER BY " + JdbcPkBoundaryScanner.quote(pk);
            List<RowRecord> records = new ArrayList<>();
            try (PreparedStatement ps = src.prepareStatement(sql)) {
                if (chunk.lowerExclusive() == null) {
                    ps.setObject(1, chunk.upperInclusive());
                } else {
                    ps.setObject(1, chunk.lowerExclusive());
                    ps.setObject(2, chunk.upperInclusive());
                }
                ps.setFetchSize(chunkSize);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<String> cols = new ArrayList<>(n);
                    for (int i = 1; i <= n; i++) cols.add(md.getColumnLabel(i));
                    while (rs.next()) {
                        List<Object> vals = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) vals.add(rs.getObject(i));
                        records.add(new RowRecord(schema, table, cols, vals, OpType.UPSERT,
                                "chunk-" + chunk.shardNo()));
                    }
                }
            }
            if (records.isEmpty()) {
                checkpointStore.markChunkDone(taskId, chunk.upperKey());
                return;
            }
            sink.writeBatch(records, null);                                  // ① 先写数据
            checkpointStore.markChunkDone(taskId, chunk.upperKey());          // ② 再落断点
            rowsCopied.addAndGet(records.size());
            chunksDone.incrementAndGet();
            limiter.acquire(records.size());
        } catch (Exception e) {
            throw new DtsException("分片 " + chunk.shardNo() + " (≤" + chunk.upperKey() + ") 失败: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
