package com.minioms.store;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.GtidEventData;
import com.github.shyiko.mysql.binlog.event.QueryEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.minioms.common.Connections;
import com.minioms.common.DbEndpoint;
import com.minioms.common.DdlEvent;
import com.minioms.common.OmsException;
import com.minioms.common.GtidState;
import com.minioms.common.JdbcUrl;
import com.minioms.common.PkResolver;
import com.minioms.common.RowRecord;
import com.minioms.common.SchemaFingerprint;
import com.minioms.common.TargetAdmin;
import com.minioms.common.TaskStateNotifier;
import com.minioms.connector.Sink;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * binlog 增量订阅 → 幂等回放（Store）。
 *
 * - 断点：GTID（client 从 gtidSet 接着播），回放成功后 oms_incr_checkpoint 前进（红线 R4 同事务）
 * - 事务边界：GTID..XID 聚合成一批，批内保序（红线 R5）
 * - 恢复语义：从已保存位点重播 → 至少一次；Sink 幂等 → 目标收敛
 * - 表过滤：库名 + 表名双键，只回放任务声明库里的表（同实例其它库的同名表必须隔离）
 * - 故障语义：断线自动重连（指数退避）；重连前复核表结构指纹；重试耗尽才转 ERROR（绝不静默退出）
 * - DDL：记账 + 分类。纯 ADD COLUMN 自动在目标端重放并刷新元数据；其余转人工（任务暂停）
 */
public class BinlogStoreService implements BinaryLogClient.EventListener {

    private static final Logger LOG = LoggerFactory.getLogger(BinlogStoreService.class);

    /**
     * @param maxReconnects         连续重连次数上限（超过即转 ERROR）
     * @param initialBackoffMillis  首次重连等待
     * @param maxBackoffMillis      退避上限
     * @param maxBufferedRows       单事务在内存里最多缓存的行数，超过就先落一批（防大事务 OOM）
     */
    public record Reconnect(int maxReconnects, long initialBackoffMillis, long maxBackoffMillis, int maxBufferedRows) {
        public static Reconnect defaults() {
            return new Reconnect(10, 500, 10_000, 20_000);
        }
    }

    private record TableRef(String database, String table) {
    }

    private final DbEndpoint source;
    private final String schema;                 // 任务声明的源库名
    private final List<String> tables;           // 空 = 整库（仍按库名隔离）
    private final String taskId;
    private final TaskStateNotifier notifier;
    private final TargetAdmin targetAdmin;       // 可为 null（纯订阅，不做 DDL/指纹）
    private final Reconnect reconnect;

    private volatile BinaryLogClient client;
    private volatile Sink sink;
    private volatile Supplier<Sink> sinkSupplier;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Throwable lastError;
    private volatile Throwable fatal;
    private int consecutiveEventFailures;

    public final AtomicLong txApplied = new AtomicLong();
    public final AtomicLong eventsSkippedOtherTables = new AtomicLong();
    public final AtomicLong ddlEvents = new AtomicLong();
    public final AtomicLong reconnects = new AtomicLong();

    private final Map<Long, TableRef> tableById = new ConcurrentHashMap<>();
    private volatile Map<String, List<String>> columnsByTable = Map.of();
    private volatile Map<String, List<String>> pkColumnsByTable = Map.of();

    private final List<RowRecord> txBuffer = new ArrayList<>();
    private final GtidState appliedGtids = new GtidState();
    private String currentGtid;

    public BinlogStoreService(DbEndpoint source, String schema, List<String> tables,
                              String taskId, TaskStateNotifier notifier) {
        this(source, schema, tables, taskId, notifier, null, Reconnect.defaults());
    }

    public BinlogStoreService(DbEndpoint source, String schema, List<String> tables,
                              String taskId, TaskStateNotifier notifier,
                              TargetAdmin targetAdmin, Reconnect reconnect) {
        this.source = source;
        this.schema = schema;
        this.tables = tables == null ? List.of() : List.copyOf(tables);
        this.taskId = taskId;
        this.notifier = notifier;
        this.targetAdmin = targetAdmin;
        this.reconnect = reconnect;
    }

    /** 启动（阻塞直到 stop / 重试耗尽 / 需要人工介入）。resumeFromGtid：断点恢复位点。 */
    public void start(String resumeFromGtid, Supplier<Sink> sinkSupplier) {
        if (!running.compareAndSet(false, true)) {
            throw new OmsException("Store 已在运行: " + taskId);
        }
        this.sinkSupplier = sinkSupplier;
        try {
            preloadTableMetadata();
            verifyFingerprint();
            if (resumeFromGtid != null && !resumeFromGtid.isBlank()) {
                appliedGtids.mergeFrom(GtidState.of(resumeFromGtid));
            }
            int attempt = 0;
            while (running.get()) {
                try {
                    subscribe();       // 阻塞：直到 stop / 连接断开 / 事件致命错误
                    if (running.get()) {
                        // 连接自己断了（服务端 kill、网络中断）：当作一次失败，走重连
                        lastError = new OmsException("binlog 连接被关闭");
                    }
                } catch (Exception e) {
                    lastError = e;
                }
                if (!running.get()) {
                    if (fatal != null) {
                        fail(fatal);
                    }
                    return;
                }
                // 旧 client 可能还活着（connect() 返回不代表它已经停了），不关掉会堆积 keepalive 重连
                disconnectQuietly();
                client = null;
                if (++attempt > reconnect.maxReconnects()) {
                    fail(new OmsException("重连 " + reconnect.maxReconnects() + " 次仍失败: " + message(lastError)));
                    return;
                }
                reconnects.incrementAndGet();
                LOG.warn("binlog 订阅中断（第 {} 次重连），原因: {}", attempt, message(lastError));
                backoff(attempt);
                refreshAfterReconnect();
            }
        } catch (Exception e) {
            fail(e);
        } finally {
            running.set(false);
            closeSink();
        }
    }

    /** 主动停止（pause / finish）。不触发 onError。 */
    public void stop() {
        running.set(false);
        BinaryLogClient c = client;
        if (c != null) {
            try {
                c.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Throwable failure() {
        return fatal != null ? fatal : lastError;
    }

    // ── 订阅与重连 ────────────────────────────────────────────────

    private void subscribe() throws Exception {
        if (sink == null) {
            sink = sinkSupplier.get();
        }
        BinaryLogClient c = buildClient();
        this.client = c;
        c.registerEventListener(this);
        c.connect();
    }

    private void backoff(int attempt) {
        long delay = Math.min(reconnect.maxBackoffMillis(),
                reconnect.initialBackoffMillis() * (1L << Math.min(attempt - 1, 10)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 重连前：连接可能已被替换，元数据与结构指纹都要复核（源端可能在这段时间变了结构）。 */
    private void refreshAfterReconnect() {
        try {
            closeSink();
            sink = null;
            preloadTableMetadata();
            verifyFingerprint();
        } catch (Exception e) {
            fatal = e;
            running.set(false);
        }
    }

    private void fail(Throwable e) {
        fatal = e;
        running.set(false);
        notifier.onError(taskId, "增量同步失败/退出: " + message(e));
    }

    // ── BinaryLogClient.EventListener ──────────────────────────────

    @Override
    public void onEvent(Event event) {
        try {
            handle(event);
            consecutiveEventFailures = 0;
        } catch (Exception e) {
            lastError = e;
            consecutiveEventFailures++;
            LOG.warn("增量事件处理失败（第 {} 次）: {}", consecutiveEventFailures, message(e), e);
            if (consecutiveEventFailures > reconnect.maxReconnects()) {
                fatal = e;      // 连续失败达上限：交给外层转 ERROR
                running.set(false);
            }
            disconnectQuietly();
        }
    }

    private void disconnectQuietly() {
        BinaryLogClient c = client;
        if (c != null) {
            try {
                c.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    private void handle(Event event) throws Exception {
        EventHeader header = event.getHeader();
        EventType type = header.getEventType();
        EventData data = event.getData();

        if (type == EventType.TABLE_MAP) {
            TableMapEventData tm = (TableMapEventData) data;
            tableById.put(tm.getTableId(), new TableRef(tm.getDatabase(), tm.getTable()));
        } else if (type == EventType.GTID) {
            currentGtid = ((GtidEventData) data).getGtid();
            txBuffer.clear();
        } else if (type == EventType.QUERY) {
            onQuery((QueryEventData) data);
        } else if (type == EventType.WRITE_ROWS || type == EventType.EXT_WRITE_ROWS) {
            WriteRowsEventData w = (WriteRowsEventData) data;
            for (Serializable[] row : w.getRows()) {
                forTarget(w.getTableId(), (cols, ref) ->
                        add(BinlogRecordMapper.write(ref.database(), ref.table(), cols, row, currentGtid)));
            }
        } else if (type == EventType.UPDATE_ROWS || type == EventType.EXT_UPDATE_ROWS) {
            UpdateRowsEventData u = (UpdateRowsEventData) data;
            for (var entry : u.getRows()) {
                Serializable[] before = entry.getKey();
                Serializable[] after = entry.getValue();
                forTarget(u.getTableId(), (cols, ref) -> add(BinlogRecordMapper.update(
                        ref.database(), ref.table(), cols, pkColumnsByTable.getOrDefault(ref.table(), List.of()),
                        before, after, currentGtid)));
            }
        } else if (type == EventType.DELETE_ROWS || type == EventType.EXT_DELETE_ROWS) {
            DeleteRowsEventData d = (DeleteRowsEventData) data;
            for (Serializable[] row : d.getRows()) {
                forTarget(d.getTableId(), (cols, ref) ->
                        add(BinlogRecordMapper.delete(ref.database(), ref.table(), cols, row, currentGtid)));
            }
        } else if (type == EventType.XID) {
            flushTx();
        }
        // ROTATE / FORMAT_DESCRIPTION 等：非数据事件，忽略
    }

    /** 追加到事务缓冲；超过内存上限就先落一批（位点不动），保证大事务不会撑爆堆。 */
    private void add(List<RowRecord> records) throws Exception {
        txBuffer.addAll(records);
        if (txBuffer.size() >= reconnect.maxBufferedRows()) {
            Sink s = requireSink();
            s.writeBatch(new ArrayList<>(txBuffer), null);
            txBuffer.clear();
        }
    }

    // ── DDL ───────────────────────────────────────────────────────

    private void onQuery(QueryEventData query) {
        String sql = query.getSql();
        DdlClassifier.Result result = DdlClassifier.classify(sql);
        if (result.kind() == DdlClassifier.Kind.IGNORE) {
            return;
        }
        String database = query.getDatabase() != null ? query.getDatabase() : schema;
        if (!inScopeDatabase(database)) {
            return;                                  // 其它库的 DDL 与本任务无关
        }
        if (result.table() != null && !inScopeTable(result.table())) {
            return;
        }
        ddlEvents.incrementAndGet();
        if (targetAdmin == null) {
            notifier.onSchemaChange(taskId, "检测到 DDL（未装配目标端管理端口）: " + sql);
            stop();
            return;
        }
        if (result.autoApplicable()) {
            applyWhitelistedDdl(result, database, sql);
        } else {
            String reason = result.kind() == DdlClassifier.Kind.OTHER_DDL
                    ? "非白名单 DDL，需人工在目标端执行后 resume"
                    : "非 DDL 语句（可能改变结构或权限），需人工确认";
            journal(result, database, sql, false, reason);
            notifier.onSchemaChange(taskId, reason + " | " + sql);
            stop();
        }
    }

    private void applyWhitelistedDdl(DdlClassifier.Result result, String database, String sql) {
        try {
            targetAdmin.applyDdl(schema != null ? schema : database, sql);
            preloadTableMetadata();
            verifyFingerprint();
            journal(result, database, sql, true, "白名单 DDL（ADD COLUMN）已自动在目标端执行");
        } catch (Exception e) {
            journal(result, database, sql, false, "自动执行失败: " + message(e));
            notifier.onSchemaChange(taskId, "白名单 DDL 自动执行失败: " + message(e) + " | " + sql);
            stop();
        }
    }

    private void journal(DdlClassifier.Result result, String database, String sql, boolean applied, String reason) {
        try {
            targetAdmin.journal(new DdlEvent(taskId, result.kind().name(), database, result.table(),
                    sql, currentGtid, applied, reason));
        } catch (Exception e) {
            LOG.warn("DDL 台账写入失败（不阻断主流程）: {}", message(e), e);
        }
    }

    // ── 事务提交 ──────────────────────────────────────────────────

    void flushTx() throws Exception {
        if (currentGtid == null) {
            LOG.warn("事务无 GTID（gtid_mode 未开？），跳过——环境预检应已拦截");
            return;
        }
        try {
            String[] sidGno = currentGtid.split(":");
            appliedGtids.add(sidGno[0], Long.parseLong(sidGno[1]));
            // 位点前进 + 数据写入同事务（红线 R4，在 MySqlSink.doWrite 内保证）
            requireSink().writeBatch(new ArrayList<>(txBuffer), appliedGtids.serialize());
            txApplied.incrementAndGet();
        } finally {
            txBuffer.clear();
        }
    }

    private Sink requireSink() {
        Sink s = sink;
        if (s == null) {
            throw new OmsException("目标写入端口未就绪");
        }
        return s;
    }

    // ── 元数据 / 指纹 / 作用域 ─────────────────────────────────────

    private void preloadTableMetadata() {
        Map<String, List<String>> cols = new HashMap<>();
        Map<String, List<String>> pks = new HashMap<>();
        try (Connection conn = Connections.open(source)) {
            List<String> names = tables.isEmpty() ? listTables(conn) : tables;
            for (String t : names) {
                cols.put(t, PkResolver.resolveColumns(conn, schema, t));
                pks.put(t, PkResolver.resolvePkColumns(conn, schema, t));
            }
        } catch (Exception e) {
            throw new OmsException("预载表元数据失败: " + e.getMessage(), e);
        }
        this.columnsByTable = Map.copyOf(cols);
        this.pkColumnsByTable = Map.copyOf(pks);
    }

    /** 源端结构指纹复核：建任务时登记在目标库，这里逐表比对（防「源端被手工改结构」静默漂移）。 */
    private void verifyFingerprint() {
        if (targetAdmin == null) {
            return;
        }
        Map<String, String> registered = targetAdmin.fingerprints(taskId);
        if (registered.isEmpty()) {
            return;      // 老任务没登记：不阻断，下次重建任务时补
        }
        Map<String, String> current = new LinkedHashMap<>();
        try (Connection conn = Connections.open(source)) {
            for (String table : columnsByTable.keySet()) {
                current.put(table, SchemaFingerprint.read(conn, schema, table));
            }
        } catch (Exception e) {
            throw new OmsException("读取源端结构指纹失败: " + e.getMessage(), e);
        }
        for (Map.Entry<String, String> e : current.entrySet()) {
            String expected = registered.get(e.getKey());
            if (expected != null && !expected.equals(e.getValue())) {
                throw new OmsException("表结构已变更（源端指纹与建任务时不一致）: " + e.getKey()
                        + " 期望=" + expected + " 实际=" + e.getValue()
                        + " —— 请在目标端对齐结构后 resume");
            }
        }
        targetAdmin.putFingerprints(taskId, current);   // 白名单 DDL 后新结构即新基线
    }

    private List<String> listTables(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE='BASE TABLE'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }

    private BinaryLogClient buildClient() throws IOException {
        URI uri = URI.create(source.url().replaceFirst("^jdbc:", ""));
        BinaryLogClient c = new BinaryLogClient(
                uri.getHost(), uri.getPort() == -1 ? 3306 : uri.getPort(), source.user(), source.password());
        // serverId 必须稳定且尽量不撞：撞了 MySQL 会把对方踢下线，表现为「连上就断」。
        // 用 taskId 派生（同一任务在任何节点上都是同一个 id，重复启动也不会互相踢）。
        c.setServerId(Math.abs(taskId.hashCode()) % 100_000 + 1);
        // 关掉客户端内置的 keepalive 重连：外层已有重连循环，两套重连会互相打断
        //（实测现象：连上→立即返回→外层重连，前几次一直抖动）
        c.setKeepAlive(false);
        String gtidSet = appliedGtids.isEmpty() ? currentGtidExecuted() : appliedGtids.serialize();
        c.setGtidSet(gtidSet);
        return c;
    }

    private String currentGtidExecuted() {
        try (Connection conn = Connections.open(source)) {
            return com.minioms.common.MasterStatusReader.readGtidExecuted(conn);
        } catch (Exception e) {
            throw new OmsException("读取源库 gtid_executed 失败: " + e.getMessage(), e);
        }
    }

    private interface TableAction {
        void accept(List<String> columns, TableRef ref) throws Exception;
    }

    private void forTarget(long tableId, TableAction action) throws Exception {
        TableRef ref = tableById.get(tableId);
        if (ref == null || !inScope(ref)) {
            eventsSkippedOtherTables.incrementAndGet();
            return;
        }
        List<String> cols = columnsByTable.get(ref.table());
        if (cols == null) {
            throw new OmsException("表 " + ref.table() + " 缺少列元数据（结构变更未处理？）");
        }
        action.accept(cols, ref);
    }

    private boolean inScope(TableRef ref) {
        return inScopeDatabase(ref.database()) && inScopeTable(ref.table());
    }

    private boolean inScopeDatabase(String database) {
        return schema == null || database == null || JdbcUrl.sameDatabase(schema, database);
    }

    private boolean inScopeTable(String table) {
        return tables.isEmpty() || tables.contains(table);
    }

    private void closeSink() {
        Sink s = sink;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
            sink = null;
        }
    }

    private static String message(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
