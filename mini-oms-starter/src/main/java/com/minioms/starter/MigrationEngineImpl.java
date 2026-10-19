package com.minioms.starter;

import com.minioms.cm.MigrationEngine;
import com.minioms.cm.TaskLeaseManager;
import com.minioms.cm.TaskMetadata;
import com.minioms.cm.TaskRepository;
import com.minioms.cm.TaskState;
import com.minioms.cm.TaskStateMachine;
import com.minioms.cm.VerifyReportRepository;
import com.minioms.common.ColumnType;
import com.minioms.common.ColumnTypes;
import com.minioms.common.Connections;
import com.minioms.common.DbEndpoint;
import com.minioms.common.OmsException;
import com.minioms.common.GlobalRateLimiter;
import com.minioms.common.JdbcUrl;
import com.minioms.common.MasterStatusReader;
import com.minioms.common.PkCodec;
import com.minioms.common.PkResolver;
import com.minioms.common.RateLimiter;
import com.minioms.common.SchemaFingerprint;
import com.minioms.common.TaskStateNotifier;
import com.minioms.connector.MySqlSink;
import com.minioms.connector.SqlDialectFactory;
import com.minioms.connector.TargetAdminImpl;
import com.minioms.connector.TypeCompatibility;
import com.minioms.connector.TypeMapper;
import com.minioms.connector.TypeMappers;
import com.minioms.connector.TargetFullCheckpointStore;
import com.minioms.connector.TargetSchemaInit;
import com.minioms.full.FullTransferService;
import com.minioms.store.BinlogStoreService;
import com.minioms.store.MySqlEnvChecker;
import com.minioms.verifier.VerifyService;
import com.minioms.verifier.VerifySummary;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * 控制面编排：装配 common/connector/full/store/verifier 各数据面模块。
 *
 * 时序契约（红线）：
 *  1. 全量前记录一致性位点（R3）：consistency_gtid
 *  2. 全量：数据先写、断点后落
 *  3. 全量完成 → 增量从【一致性位点】起播（覆盖全量窗口内的所有写入，幂等收敛）
 *  4. 增量事务：数据 + oms_incr_checkpoint 同事务（R4）
 *
 * 多节点契约（不再是单机玩具的部分）：
 *  - 谁能跑某个任务由 {@link TaskLeaseManager} 的租约决定，不靠进程内 synchronized
 *  - 全量断点在目标库、增量位点在目标库 —— 换节点接管都能接着跑
 *  - 进程重启后 start 恢复扫描把 RUNNING_* 的任务重挂回来（{@link #reattach}）
 *  - 实例级全局限流 + 任务并发上限，避免多任务叠加速度把源库打穿
 */
public class MigrationEngineImpl implements MigrationEngine, TaskStateNotifier {

    private static final Logger LOG = Logger.getLogger(MigrationEngineImpl.class.getName());

    private final TaskRepository tasks;
    private final VerifyReportRepository verifyReports;
    private final TaskLeaseManager leases;
    private final GlobalRateLimiter globalLimiter;
    private final EngineProperties props;

    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    private final Set<String> slots = ConcurrentHashMap.newKeySet();

    /** 一个任务的运行时资源：目标端管理连接、全量断点连接、当前数据面服务。 */
    static final class Runtime implements AutoCloseable {
        private final TargetAdminImpl admin;
        private final Connection checkpointConnection;
        private final TargetFullCheckpointStore checkpoints;
        private final Map<String, List<String>> pkByTable;
        private final String targetSchema;
        volatile FullTransferService full;
        volatile BinlogStoreService store;

        Runtime(TargetAdminImpl admin, Connection checkpointConnection,
                TargetFullCheckpointStore checkpoints, Map<String, List<String>> pkByTable, String targetSchema) {
            this.admin = admin;
            this.checkpointConnection = checkpointConnection;
            this.checkpoints = checkpoints;
            this.pkByTable = pkByTable;
            this.targetSchema = targetSchema;
        }

        @Override
        public void close() {
            FullTransferService f = full;
            if (f != null) {
                f.stop();
            }
            BinlogStoreService s = store;
            if (s != null) {
                s.stop();
            }
            closeQuietly(checkpointConnection);
            if (admin != null) {
                admin.close();
            }
        }

        private static void closeQuietly(AutoCloseable c) {
            try {
                if (c != null) {
                    c.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public MigrationEngineImpl(TaskRepository tasks, VerifyReportRepository verifyReports,
                               TaskLeaseManager leases, GlobalRateLimiter globalLimiter, EngineProperties props) {
        this.tasks = tasks;
        this.verifyReports = verifyReports;
        this.leases = leases;
        this.globalLimiter = globalLimiter;
        this.props = props;
    }

    // ── 创建 ─────────────────────────────────────────────────────

    @Override
    public String createTask(TaskCreateRequest req) {
        if (req.sourceUrl() == null || req.targetUrl() == null || req.schema() == null) {
            throw new OmsException("sourceUrl / targetUrl / schema 必填");
        }
        requireSameDatabase(req.sourceUrl(), req.schema(), "sourceUrl");
        requireSameDatabase(req.targetUrl(), req.schema(), "targetUrl");

        DbEndpoint source = new DbEndpoint(req.sourceUrl(), req.sourceUser(), req.sourcePassword());
        DbEndpoint target = new DbEndpoint(req.targetUrl(), req.targetUser(), req.targetPassword());

        MySqlEnvChecker.check(source);   // ROW / FULL / GTID —— 不满足直接拒绝建任务

        List<String> tables = req.tables() == null
                ? List.of() : req.tables().stream().filter(s -> !s.isBlank()).toList();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        Map<String, List<ColumnType>> sourceTypes = new LinkedHashMap<>();
        try (Connection src = Connections.open(source); Connection tgt = Connections.open(target)) {
            if (tables.isEmpty()) {
                tables = listAllTables(src, req.schema());
            }
            if (tables.isEmpty()) {
                throw new OmsException("schema " + req.schema() + " 下没有基表");
            }
            for (String t : tables) {
                List<String> srcPk = PkResolver.resolvePkColumns(src, req.schema(), t);
                List<String> tgtPk = PkResolver.resolvePkColumns(tgt, req.schema(), t);
                if (!srcPk.equals(tgtPk)) {
                    throw new OmsException("主键不一致（幂等写前提）: " + t + " 源=" + srcPk + " 目标=" + tgtPk);
                }
                if (srcPk.size() > 1) {
                    throw new OmsException("复合主键暂不支持: " + t + " " + srcPk);
                }
                String type = PkResolver.pkTypeName(src, req.schema(), t);
                if (!PkCodec.supported(type)) {
                    throw new OmsException("主键类型不支持: " + t + "." + type);
                }
                String srcFp = SchemaFingerprint.read(src, req.schema(), t);
                String tgtFp = SchemaFingerprint.read(tgt, req.schema(), t);
                if (!srcFp.equals(tgtFp)) {
                    throw new OmsException("表结构不一致（迁移前提）: " + t
                            + "\n 源=" + SchemaFingerprint.columns(src, req.schema(), t)
                            + "\n 目标=" + SchemaFingerprint.columns(tgt, req.schema(), t));
                }
                fingerprints.put(t, srcFp);
                sourceTypes.put(t, ColumnTypes.read(src, req.schema(), t));
            }
            // 迁移评估（OMS 同名能力的 mini 版）：只读元数据，把「跑到一半才发现类型不兼容」提前到第 0 秒
            TypeMapper mapper = TypeMappers.forDialect(SqlDialectFactory.detect(tgt));
            List<String> typeProblems = TypeCompatibility.problems(sourceTypes, mapper);
            if (!typeProblems.isEmpty()) {
                throw new OmsException("源库类型无法自动迁移到目标库（" + mapper.name() + "）：\n - "
                        + String.join("\n - ", typeProblems));
            }
        } catch (OmsException e) {
            throw e;
        } catch (Exception e) {
            throw new OmsException("连通性/元数据校验失败: " + e.getMessage(), e);
        }

        String id = "t" + Long.toString(System.currentTimeMillis(), 36);
        String now = java.time.LocalDateTime.now().withNano(0).toString();
        TaskMetadata meta = new TaskMetadata(id,
                req.sourceUrl(), req.sourceUser(), req.sourcePassword(),
                req.targetUrl(), req.targetUser(), req.targetPassword(),
                req.schema(), tables,
                req.chunkSize() <= 0 ? 1000 : req.chunkSize(),
                req.parallelism() <= 0 ? 4 : req.parallelism(),
                req.rowsPerSecond() <= 0 ? 50_000 : req.rowsPerSecond(),
                TaskState.INIT, false, null, null, now, now);
        tasks.insert(meta);

        // 结构指纹登记在目标库：增量期任何时候发现源端结构变了，都能在开始写之前拦住
        try (TargetAdminImpl admin = TargetAdminImpl.open(target)) {
            admin.putFingerprints(id, fingerprints);
        }
        return id;
    }

    // ── 生命周期 ──────────────────────────────────────────────────

    @Override
    public synchronized void start(String taskId) {
        TaskMetadata t = load(taskId);
        if (t.state() == TaskState.RUNNING_FULL || t.state() == TaskState.RUNNING_INCR) {
            throw new OmsException("任务已在运行中");
        }
        if (t.state() == TaskState.INIT) {
            acquire(taskId);
            TaskStateMachine.transition(t.state(), TaskState.RUNNING_FULL);
            String consistency = readConsistency(t);
            tasks.updateConsistencyGtid(taskId, consistency);
            tasks.updateState(taskId, TaskState.RUNNING_FULL, false, null);
            launchFull(t.withFullDone(false, consistency));
        } else if (t.state() == TaskState.FULL_DONE) {
            acquire(taskId);
            TaskStateMachine.transition(t.state(), TaskState.RUNNING_INCR);
            tasks.updateState(taskId, TaskState.RUNNING_INCR, true, null);
            launchIncr(t, t.consistencyGtid());
        } else {
            throw new OmsException("当前状态 " + t.state() + " 不可 start（PAUSED/ERROR 请用 resume）");
        }
    }

    @Override
    public synchronized void pause(String taskId) {
        TaskMetadata t = load(taskId);
        TaskStateMachine.transition(t.state(), TaskState.PAUSED);
        tasks.updateState(taskId, TaskState.PAUSED, t.fullDone(), null);
        stopRuntime(taskId);
    }

    @Override
    public synchronized void resume(String taskId) {
        TaskMetadata t = load(taskId);
        if (runtimes.containsKey(taskId)) {
            throw new OmsException("任务已有运行时，无需 resume");
        }
        TaskState to = TaskStateMachine.resumeTo(t.fullDone());
        // reattach 而不是 transition：崩溃后残留的 RUNNING_* 允许原地重挂
        TaskStateMachine.reattach(t.state(), to);
        acquire(taskId);
        tasks.updateState(taskId, to, t.fullDone(), null);
        if (to == TaskState.RUNNING_FULL) {
            launchFull(t);
        } else {
            launchIncr(t, incrResumePoint(t));
        }
    }

    @Override
    public synchronized boolean reattach(String taskId) {
        TaskMetadata t = tasks.find(taskId).orElse(null);
        if (t == null) {
            return false;
        }
        if (t.state() != TaskState.RUNNING_FULL && t.state() != TaskState.RUNNING_INCR) {
            return false;
        }
        if (runtimes.containsKey(taskId)) {
            return false;                       // 本节点已经在跑
        }
        if (slots.size() >= props.maxConcurrentTasks()) {
            LOG.warning("并发上限已满，接管延后: " + taskId);
            return false;
        }
        if (!leases.acquire(taskId)) {
            LOG.info("任务 " + taskId + " 的租约在别的节点（" + leases.owner(taskId).orElse("?") + "），本节点不接管");
            return false;
        }
        slots.add(taskId);
        if (t.state() == TaskState.RUNNING_INCR) {
            launchIncr(t, incrResumePoint(t));
        } else {
            launchFull(t);
        }
        return true;
    }

    @Override
    public synchronized void finish(String taskId) {
        TaskMetadata t = load(taskId);
        TaskStateMachine.transition(t.state(), TaskState.FINISHED);
        tasks.updateState(taskId, TaskState.FINISHED, true, null);
        stopRuntime(taskId);
    }

    @Override
    public Map<String, Object> verify(String taskId) {
        TaskMetadata t = load(taskId);
        Map<String, Object> out = new LinkedHashMap<>();
        boolean allEqual = true;
        for (String table : t.tables()) {
            VerifyService svc = new VerifyService(
                    endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()),
                    endpoint(t.targetUrl(), t.targetUser(), t.targetPassword()),
                    t.schema(), table, t.chunkSize(), verifyReports);
            VerifySummary s = svc.verify(taskId);
            allEqual &= s.allEqual();
            Map<String, Object> per = new LinkedHashMap<>();
            per.put("chunks", s.chunks());
            per.put("equal", s.equalChunks());
            per.put("mismatched", s.mismatchedChunks());
            if (!s.allEqual()) {
                per.put("diffs", s.diffs().stream().map(String::valueOf).limit(50).toList());
            }
            out.put(table, per);
        }
        out.put("allEqual", allEqual);
        return out;
    }

    @Override
    public Map<String, Object> metrics(String taskId) {
        TaskMetadata t = load(taskId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", t.state().name());
        m.put("fullDone", t.fullDone());
        m.put("node", leases.nodeId());
        m.put("leaseOwner", leases.owner(taskId).orElse(null));
        Runtime rt = runtimes.get(taskId);
        if (rt != null) {
            if (rt.full != null) {
                m.put("rowsCopied", rt.full.rowsCopied.get());
                m.put("chunksDone", rt.full.chunksDone.get());
                m.put("chunksSkipped", rt.full.chunksSkipped.get());
            }
            if (rt.store != null) {
                m.put("incrTxApplied", rt.store.txApplied.get());
                m.put("incrDdlEvents", rt.store.ddlEvents.get());
                m.put("incrReconnects", rt.store.reconnects.get());
                m.put("incrRunning", rt.store.isRunning());
                if (rt.store.failure() != null) {
                    m.put("incrLastError", String.valueOf(rt.store.failure()));
                }
            }
        }
        m.put("fullCheckpoints", countFullCheckpoints(t));
        String ckpt = incrResumePointQuiet(t);
        if (ckpt != null) {
            m.put("incrCheckpointGtid", ckpt);
        }
        m.put("verifyDiffs", verifyReports.diffCount(taskId));
        return m;
    }

    // ── 数据面回调（TaskStateNotifier） ───────────────────────────

    @Override
    public void onFullDone(String taskId) {
        TaskMetadata t = load(taskId);
        if (t.state() != TaskState.RUNNING_FULL) {
            return;   // 已被 pause / 重复回调
        }
        TaskStateMachine.transition(TaskState.RUNNING_FULL, TaskState.FULL_DONE);
        tasks.updateState(taskId, TaskState.FULL_DONE, true, null);
        // 全量完成 → 自动进入增量（从一致性位点起播）
        TaskStateMachine.transition(TaskState.FULL_DONE, TaskState.RUNNING_INCR);
        tasks.updateState(taskId, TaskState.RUNNING_INCR, true, null);
        launchIncr(load(taskId), t.consistencyGtid());
    }

    @Override
    public void onError(String taskId, String reason) {
        TaskMetadata t = tasks.find(taskId).orElse(null);
        if (t == null || t.state() == TaskState.FINISHED) {
            return;
        }
        try {
            TaskStateMachine.transition(t.state(), TaskState.ERROR);
            tasks.updateState(taskId, TaskState.ERROR, t.fullDone(), reason);
        } catch (OmsException ignored) {
            // PAUSED/FINISHED 下的迟到失败回调：保留现场，不改状态
        }
        stopRuntime(taskId);
    }

    /** 源端结构变更：转 PAUSED 等人工确认（不是 ERROR —— 数据可能没坏，只是需要人看一眼）。 */
    @Override
    public void onSchemaChange(String taskId, String detail) {
        TaskMetadata t = tasks.find(taskId).orElse(null);
        if (t == null) {
            return;
        }
        try {
            TaskStateMachine.transition(t.state(), TaskState.PAUSED);
            tasks.updateState(taskId, TaskState.PAUSED, t.fullDone(), "源端结构变更，待人工处理: " + detail);
        } catch (OmsException e) {
            tasks.updateState(taskId, t.state(), t.fullDone(), "源端结构变更: " + detail);
        }
        stopRuntime(taskId);
    }

    // ── 租约 / 并发槽 ────────────────────────────────────────────

    /** 续租；返回丢失了租约的任务（别的节点接管了，本节点必须停手）。 */
    public Set<String> renewLeases() {
        Set<String> lost = leases.renewAll();
        for (String taskId : lost) {
            LOG.warning("任务 " + taskId + " 租约丢失（已被接管），停止本节点运行时");
            stopRuntime(taskId);
        }
        return lost;
    }

    private void acquire(String taskId) {
        if (!slots.contains(taskId) && slots.size() >= props.maxConcurrentTasks()) {
            throw new OmsException("并发任务上限已达 " + props.maxConcurrentTasks() + "，请稍后重试");
        }
        if (!leases.acquire(taskId)) {
            throw new OmsException("任务 " + taskId + " 正被节点 " + leases.owner(taskId).orElse("?")
                    + " 持有（租约 " + props.leaseTtl().toSeconds() + "s），拒绝重复启动");
        }
        slots.add(taskId);
    }

    private void stopRuntime(String taskId) {
        leases.release(taskId);
        slots.remove(taskId);
        Runtime rt = runtimes.remove(taskId);
        if (rt != null) {
            rt.close();
        }
    }

    // ── 内部装配 ──────────────────────────────────────────────────

    private void launchFull(TaskMetadata t) {
        Runtime rt = runtime(t);
        List<String> tables = t.tables();
        AtomicInteger completed = new AtomicInteger();
        TaskStateNotifier agg = new TaskStateNotifier() {
            @Override
            public void onFullDone(String taskId) {
                if (completed.incrementAndGet() == tables.size()) {
                    MigrationEngineImpl.this.onFullDone(taskId);
                }
            }

            @Override
            public void onError(String taskId, String reason) {
                MigrationEngineImpl.this.onError(taskId, reason);
            }
        };
        Thread thread = new Thread(() -> {
            try {
                for (int i = 0; i < tables.size(); i++) {
                    TaskMetadata cur = load(t.id());
                    if (cur.state() != TaskState.RUNNING_FULL) {
                        return;   // 被暂停 / 出错
                    }
                    FullTransferService svc = fullServiceFor(cur, tables.get(i), agg);
                    rt.full = svc;
                    svc.run();
                }
            } catch (Exception e) {
                onError(t.id(), e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "full-" + t.id());
        thread.start();
    }

    private void launchIncr(TaskMetadata t, String resumeGtid) {
        Runtime rt = runtime(t);
        BinlogStoreService store = new BinlogStoreService(
                endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()),
                t.schema(), t.tables(), t.id(), this, rt.admin, props.reconnect());
        rt.store = store;
        Thread thread = new Thread(() -> {
            try {
                store.start(resumeGtid, () -> new MySqlSink(
                        Connections.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword())),
                        rt.targetSchema, t.id(), rt.pkByTable));
            } catch (Exception e) {
                onError(t.id(), "增量启动失败: " + e.getMessage());
            }
        }, "incr-" + t.id());
        thread.start();
    }

    /** 懒建运行时资源（目标端管理连接 + 全量断点连接 + 主键表）。 */
    private Runtime runtime(TaskMetadata t) {
        return runtimes.computeIfAbsent(t.id(), id -> {
            TargetAdminImpl admin = TargetAdminImpl.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword()));
            Connection checkpointConn = Connections.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword()));
            try {
                TargetSchemaInit.ensure(checkpointConn);
            } catch (Exception e) {
                throw new OmsException("初始化目标库断点表失败: " + e.getMessage(), e);
            }
            return new Runtime(admin, checkpointConn, new TargetFullCheckpointStore(checkpointConn),
                    pkMap(t), JdbcUrl.databaseOf(t.targetUrl()));
        });
    }

    private FullTransferService fullServiceFor(TaskMetadata t, String table, TaskStateNotifier notifier) {
        Runtime rt = runtime(t);
        RateLimiter limiter = globalLimiter.forTask(t.rowsPerSecond());
        return new FullTransferService(
                t.id(),
                endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()),
                t.schema(), table, t.chunkSize(), t.parallelism(), limiter,
                rt.checkpoints,
                () -> new MySqlSink(
                        Connections.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword())),
                        rt.targetSchema, t.id(), rt.pkByTable),
                notifier);
    }

    private Map<String, List<String>> pkMap(TaskMetadata t) {
        try (Connection src = Connections.open(endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()))) {
            Map<String, List<String>> map = new LinkedHashMap<>();
            for (String table : t.tables()) {
                map.put(table, PkResolver.resolvePkColumns(src, t.schema(), table));
            }
            return map;
        } catch (Exception e) {
            throw new OmsException("解析主键失败: " + e.getMessage(), e);
        }
    }

    private String readConsistency(TaskMetadata t) {
        try (Connection src = Connections.open(endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()))) {
            return MasterStatusReader.readGtidExecuted(src);
        } catch (Exception e) {
            throw new OmsException("读取一致性位点失败: " + e.getMessage(), e);
        }
    }

    /** 增量恢复点：目标库 oms_incr_checkpoint（R4 同事务保证它 ≤ 已写入数据），无则回退一致性位点。 */
    private String incrResumePoint(TaskMetadata t) {
        String ckpt = incrResumePointQuiet(t);
        return ckpt != null ? ckpt : t.consistencyGtid();
    }

    private String incrResumePointQuiet(TaskMetadata t) {
        try (Connection tgt = Connections.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword()))) {
            return TargetSchemaInit.checkpointValue(tgt, t.id());
        } catch (Exception e) {
            return null;
        }
    }

    private int countFullCheckpoints(TaskMetadata t) {
        Runtime rt = runtimes.get(t.id());
        if (rt == null) {
            return 0;
        }
        return rt.checkpoints.completedUpperPks(t.id()).size();
    }

    private static void requireSameDatabase(String url, String schema, String label) {
        String db = JdbcUrl.databaseOf(url);
        if (db == null) {
            throw new OmsException(label + " 必须带库名（形如 jdbc:mysql://host:3306/" + schema + "）");
        }
        if (!JdbcUrl.sameDatabase(db, schema)) {
            throw new OmsException(label + " 的库名 " + db + " 与任务 schema " + schema
                    + " 不一致 —— 源、目标、schema 三者必须同名（本版不支持改库名迁移）");
        }
    }

    private static DbEndpoint endpoint(String url, String user, String password) {
        return new DbEndpoint(url, user, password);
    }

    private TaskMetadata load(String taskId) {
        return tasks.find(taskId).orElseThrow(() -> new OmsException("任务不存在: " + taskId));
    }

    private static List<String> listAllTables(Connection conn, String schema) throws Exception {
        List<String> names = new ArrayList<>();
        try (var ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE='BASE TABLE'")) {
            ps.setString(1, schema);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }
}
