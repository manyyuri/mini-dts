package com.minidts.store;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.GtidEventData;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.minidts.common.Connections;
import com.minidts.common.DbEndpoint;
import com.minidts.common.DtsException;
import com.minidts.common.GtidState;
import com.minidts.common.PkResolver;
import com.minidts.common.RowRecord;
import com.minidts.common.TaskStateNotifier;
import com.minidts.connector.Sink;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * binlog 增量订阅 → 幂等回放（Store）。
 *
 * - 断点：GTID（client 从 gtidSet 接着播），回放成功后 dts_incr_checkpoint 前进（红线 R4 同事务）
 * - 事务边界：GTID..XID 聚合成一批，批内保序（红线 R5）
 * - 恢复语义：从已保存位点重播 → 至少一次；Sink 幂等 → 目标收敛
 * - 表过滤：只回放 scope 内的表，其它库表忽略
 */
public class BinlogStoreService implements BinaryLogClient.EventListener {

    private static final Logger LOG = Logger.getLogger(BinlogStoreService.class.getName());

    private final DbEndpoint source;
    private final String schema;
    private final List<String> tables;      // 空 = 整库
    private final String taskId;
    private final TaskStateNotifier notifier;

    private BinaryLogClient client;
    private volatile Sink sink;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Throwable failure;

    public final AtomicLong txApplied = new AtomicLong();
    public final AtomicLong eventsSkippedOtherTables = new AtomicLong();

    // tableId → 表名（TABLE_MAP 事件建立）
    private final Map<Long, String> tableById = new ConcurrentHashMap<>();
    // 表名 → 列名 / 主键列（启动时预载）
    private volatile Map<String, List<String>> columnsByTable = Map.of();
    private volatile Map<String, List<String>> pkColumnsByTable = Map.of();

    // 事务聚合（客户端单线程回调：天然串行，保序）
    private final List<RowRecord> txBuffer = new ArrayList<>();
    private final GtidState appliedGtids = new GtidState();
    private String currentGtid;

    public BinlogStoreService(DbEndpoint source, String schema, List<String> tables,
                              String taskId, TaskStateNotifier notifier) {
        this.source = source;
        this.schema = schema;
        this.tables = tables == null ? List.of() : List.copyOf(tables);
        this.taskId = taskId;
        this.notifier = notifier;
    }

    /** 启动（阻塞直到断开/失败）。resumeFromGtid：断点恢复位点。 */
    public void start(String resumeFromGtid, java.util.function.Supplier<Sink> sinkSupplier) {
        try {
            preloadTableMetadata();
            if (resumeFromGtid != null && !resumeFromGtid.isBlank()) {
                appliedGtids.mergeFrom(GtidState.of(resumeFromGtid));
            }
            this.sink = sinkSupplier.get();
            client = buildClient(resumeFromGtid);
            client.registerEventListener(this);
            running.set(true);
            client.connect();   // 阻塞；断开即返回（stop() 或异常）
        } catch (Exception e) {
            failure = e;
            running.set(false);
            notifier.onError(taskId, "增量同步失败/退出: " + e.getMessage());
        }
    }

    public void stop() {
        running.set(false);
        if (client != null) {
            try {
                client.disconnect();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public Throwable failure() {
        return failure;
    }

    // ── BinaryLogClient.EventListener ──────────────────────────────

    @Override
    public void onEvent(Event event) {
        try {
            handle(event);
        } catch (Exception e) {
            failure = e;
            stop();   // 断开；重连后从上次提交位点重播（至少一次 → 幂等收敛）
        }
    }

    private void handle(Event event) throws Exception {
        EventHeader header = event.getHeader();
        EventType type = header.getEventType();
        EventData data = event.getData();

        if (type == EventType.TABLE_MAP) {
            TableMapEventData tm = (TableMapEventData) data;
            tableById.put(tm.getTableId(), tm.getTable());
        } else if (type == EventType.GTID) {
            currentGtid = ((GtidEventData) data).getGtid();
            txBuffer.clear();
        } else if (type == EventType.WRITE_ROWS || type == EventType.EXT_WRITE_ROWS) {
            WriteRowsEventData w = (WriteRowsEventData) data;
            for (Serializable[] row : w.getRows()) {
                forTarget(w.getTableId(), (cols, name) ->
                        txBuffer.addAll(BinlogRecordMapper.write(schema, name, cols, row, currentGtid)));
            }
        } else if (type == EventType.UPDATE_ROWS || type == EventType.EXT_UPDATE_ROWS) {
            UpdateRowsEventData u = (UpdateRowsEventData) data;
            for (var entry : u.getRows()) {
                Serializable[] before = entry.getKey();
                Serializable[] after = entry.getValue();
                forTarget(u.getTableId(), (cols, name) -> txBuffer.addAll(BinlogRecordMapper.update(
                        schema, name, cols, pkColumnsByTable.getOrDefault(name, List.of()),
                        before, after, currentGtid)));
            }
        } else if (type == EventType.DELETE_ROWS || type == EventType.EXT_DELETE_ROWS) {
            DeleteRowsEventData d = (DeleteRowsEventData) data;
            for (Serializable[] row : d.getRows()) {
                forTarget(d.getTableId(), (cols, name) ->
                        txBuffer.addAll(BinlogRecordMapper.delete(schema, name, cols, row, currentGtid)));
            }
        } else if (type == EventType.XID) {
            flushTx();
        }
        // QUERY / ROTATE / FORMAT_DESCRIPTION 等：非数据事件，忽略
    }

    private interface TableAction {
        void accept(List<String> columns, String table) throws Exception;
    }

    private void forTarget(long tableId, TableAction action) throws Exception {
        String name = tableById.get(tableId);
        if (name == null || !inScope(name)) {
            eventsSkippedOtherTables.incrementAndGet();
            return;
        }
        List<String> cols = columnsByTable.get(name);
        if (cols == null) {
            throw new DtsException("表 " + name + " 缺少列元数据（任务运行中新增表？），请重启任务重载元数据");
        }
        action.accept(cols, name);
    }

    void flushTx() throws Exception {
        if (currentGtid == null) {
            LOG.warning("事务无 GTID（gtid_mode 未开？），跳过——环境预检应已拦截");
            return;
        }
        try {
            // 位点前进 + 数据写入同事务（红线 R4，在 MySqlSink.doWrite 内保证）
            String[] sidGno = currentGtid.split(":");
            appliedGtids.add(sidGno[0], Long.parseLong(sidGno[1]));
            sink.writeBatch(txBuffer, appliedGtids.serialize());
            txApplied.incrementAndGet();
        } finally {
            txBuffer.clear();
        }
    }

    // ── 元数据预载 / 客户端构建 ─────────────────────────────────────

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
            throw new DtsException("预载表元数据失败: " + e.getMessage(), e);
        }
        this.columnsByTable = Map.copyOf(cols);
        this.pkColumnsByTable = Map.copyOf(pks);
    }

    private List<String> listTables(Connection conn) throws Exception {
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE='BASE TABLE'")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }

    private BinaryLogClient buildClient(String resumeFromGtid) throws IOException {
        URI uri = URI.create(source.url().replaceFirst("^jdbc:", ""));
        BinaryLogClient c = new BinaryLogClient(
                uri.getHost(), uri.getPort() == -1 ? 3306 : uri.getPort(), source.user(), source.password());
        c.setServerId((int) (System.currentTimeMillis() % 100000) + 1);
        c.setKeepAlive(true);
        if (resumeFromGtid != null && !resumeFromGtid.isBlank()) {
            c.setGtidSet(resumeFromGtid);
        } else {
            c.setGtidSet(currentGtidExecuted());
        }
        return c;
    }

    private String currentGtidExecuted() {
        try (Connection conn = Connections.open(source)) {
            return com.minidts.common.MasterStatusReader.readGtidExecuted(conn);
        } catch (Exception e) {
            throw new DtsException("读取源库 gtid_executed 失败: " + e.getMessage(), e);
        }
    }

    private boolean inScope(String table) {
        return tables.isEmpty() || tables.contains(table);
    }
}
