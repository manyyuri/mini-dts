package com.minidts.starter;

import com.minidts.cm.FullCheckpointRepository;
import com.minidts.cm.MigrationEngine;
import com.minidts.cm.TaskMetadata;
import com.minidts.cm.TaskRepository;
import com.minidts.cm.TaskState;
import com.minidts.cm.TaskStateMachine;
import com.minidts.cm.VerifyReportRepository;
import com.minidts.common.Connections;
import com.minidts.common.DbEndpoint;
import com.minidts.common.DtsException;
import com.minidts.common.MasterStatusReader;
import com.minidts.common.PkCodec;
import com.minidts.common.PkResolver;
import com.minidts.common.TaskStateNotifier;
import com.minidts.connector.MySqlSink;
import com.minidts.connector.TargetSchemaInit;
import com.minidts.full.FullTransferService;
import com.minidts.store.BinlogStoreService;
import com.minidts.store.MySqlEnvChecker;
import com.minidts.verifier.VerifyService;
import com.minidts.verifier.VerifySummary;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 控制面编排：装配 common/connector/full/store/verifier 各数据面模块。
 *
 * 时序契约（红线）：
 *  1. 全量前记录一致性位点（R3）：consistency_gtid
 *  2. 全量：数据先写、断点后落
 *  3. 全量完成 → 增量从【一致性位点】起播（覆盖全量窗口内的所有写入，幂等收敛）
 *  4. 增量事务：数据 + dts_incr_checkpoint 同事务（R4）
 */
public class MigrationEngineImpl implements MigrationEngine, TaskStateNotifier {

    private final TaskRepository tasks;
    private final FullCheckpointRepository checkpoints;
    private final VerifyReportRepository verifyReports;
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();

    record Runtime(FullTransferService full, BinlogStoreService store) {}

    public MigrationEngineImpl(TaskRepository tasks, FullCheckpointRepository checkpoints,
                               VerifyReportRepository verifyReports) {
        this.tasks = tasks;
        this.checkpoints = checkpoints;
        this.verifyReports = verifyReports;
    }

    // ── 创建 ─────────────────────────────────────────────────────

    @Override
    public String createTask(TaskCreateRequest req) {
        if (req.sourceUrl() == null || req.targetUrl() == null || req.schema() == null) {
            throw new DtsException("sourceUrl / targetUrl / schema 必填");
        }
        DbEndpoint source = new DbEndpoint(req.sourceUrl(), req.sourceUser(), req.sourcePassword());
        DbEndpoint target = new DbEndpoint(req.targetUrl(), req.targetUser(), req.targetPassword());

        MySqlEnvChecker.check(source);   // ROW / FULL / GTID —— 不满足直接拒绝建任务

        List<String> tables = req.tables() == null ? List.of() : req.tables().stream().filter(s -> !s.isBlank()).toList();
        try (Connection src = Connections.open(source); Connection tgt = Connections.open(target)) {
            if (tables.isEmpty()) {
                tables = listAllTables(src, req.schema());
            }
            if (tables.isEmpty()) throw new DtsException("schema " + req.schema() + " 下没有基表");
            for (String t : tables) {
                List<String> srcPk = PkResolver.resolvePkColumns(src, req.schema(), t);
                List<String> tgtPk = PkResolver.resolvePkColumns(tgt, req.schema(), t);
                if (!srcPk.equals(tgtPk)) {
                    throw new DtsException("主键不一致（幂等写前提）: " + t
                            + " 源=" + srcPk + " 目标=" + tgtPk);
                }
                if (srcPk.size() > 1) {
                    throw new DtsException("复合主键暂不支持: " + t + " " + srcPk);
                }
                String type = PkResolver.pkTypeName(src, req.schema(), t);
                if (!PkCodec.supported(type)) {
                    throw new DtsException("主键类型不支持: " + t + "." + type);
                }
            }
            TargetSchemaInit.ensure(tgt);   // dts_incr_checkpoint / dts_dead_letter 建在目标库（R2）
        } catch (DtsException e) {
            throw e;
        } catch (Exception e) {
            throw new DtsException("连通性/元数据校验失败: " + e.getMessage(), e);
        }

        String id = "t" + Long.toString(System.currentTimeMillis(), 36);
        TaskMetadata meta = new TaskMetadata(id,
                req.sourceUrl(), req.sourceUser(), req.sourcePassword(),
                req.targetUrl(), req.targetUser(), req.targetPassword(),
                req.schema(), tables,
                req.chunkSize() <= 0 ? 1000 : req.chunkSize(),
                req.parallelism() <= 0 ? 4 : req.parallelism(),
                req.rowsPerSecond() <= 0 ? 50_000 : req.rowsPerSecond(),
                TaskState.INIT, false, null, null,
                java.time.LocalDateTime.now().withNano(0).toString(),
                java.time.LocalDateTime.now().withNano(0).toString());
        tasks.insert(meta);
        return id;
    }

    // ── 生命周期 ──────────────────────────────────────────────────

    @Override
    public synchronized void start(String taskId) {
        TaskMetadata t = load(taskId);
        if (t.state() == TaskState.RUNNING_FULL || t.state() == TaskState.RUNNING_INCR) {
            throw new DtsException("任务已在运行中");
        }
        if (t.state() == TaskState.INIT) {
            TaskStateMachine.transition(t.state(), TaskState.RUNNING_FULL);
            String consistency = readConsistency(t);
            tasks.updateConsistencyGtid(taskId, consistency);
            tasks.updateState(taskId, TaskState.RUNNING_FULL, false, null);
            launchFull(t.withFullDone(false, consistency));
        } else if (t.state() == TaskState.FULL_DONE) {
            TaskStateMachine.transition(t.state(), TaskState.RUNNING_INCR);
            tasks.updateState(taskId, TaskState.RUNNING_INCR, true, null);
            launchIncr(t, t.consistencyGtid());
        } else {
            throw new DtsException("当前状态 " + t.state() + " 不可 start（PAUSED/ERROR 请用 resume）");
        }
    }

    @Override
    public synchronized void pause(String taskId) {
        TaskMetadata t = load(taskId);
        TaskStateMachine.transition(t.state(), TaskState.PAUSED);
        Runtime rt = runtimes.get(taskId);
        if (rt != null) {
            if (rt.full() != null) rt.full().stop();
            if (rt.store() != null) rt.store().stop();
        }
        tasks.updateState(taskId, TaskState.PAUSED, t.fullDone(), null);
    }

    @Override
    public synchronized void resume(String taskId) {
        TaskMetadata t = load(taskId);
        TaskState to = TaskStateMachine.resumeTo(t.fullDone());
        TaskStateMachine.transition(t.state(), to);
        if (to == TaskState.RUNNING_FULL) {
            tasks.updateState(taskId, TaskState.RUNNING_FULL, false, null);
            launchFull(t);
        } else {
            String resumeGtid = incrResumePoint(t);
            tasks.updateState(taskId, TaskState.RUNNING_INCR, true, null);
            launchIncr(t, resumeGtid);
        }
    }

    @Override
    public synchronized void finish(String taskId) {
        TaskMetadata t = load(taskId);
        TaskStateMachine.transition(t.state(), TaskState.FINISHED);
        Runtime rt = runtimes.get(taskId);
        if (rt != null && rt.store() != null) {
            rt.store().stop();
        }
        tasks.updateState(taskId, TaskState.FINISHED, true, null);
        runtimes.remove(taskId);
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
            if (!s.allEqual()) per.put("diffs", s.diffs().stream().map(String::valueOf).limit(50).toList());
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
        m.put("fullCheckpoints", checkpoints.countDone(taskId));
        Runtime rt = runtimes.get(taskId);
        if (rt != null) {
            if (rt.full() != null) {
                m.put("rowsCopied", rt.full().rowsCopied.get());
                m.put("chunksDone", rt.full().chunksDone.get());
                m.put("chunksSkipped", rt.full().chunksSkipped.get());
            }
            if (rt.store() != null) {
                m.put("incrTxApplied", rt.store().txApplied.get());
            }
        }
        String ckpt = incrResumePointQuiet(t);
        if (ckpt != null) m.put("incrCheckpointGtid", ckpt);
        m.put("verifyDiffs", verifyReports.diffCount(taskId));
        return m;
    }

    // ── 数据面回调（TaskStateNotifier） ───────────────────────────

    @Override
    public void onFullDone(String taskId) {
        TaskMetadata t = load(taskId);
        if (t.state() != TaskState.RUNNING_FULL) {
            return; // 已被 pause / 重复回调
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
        if (t == null || t.state() == TaskState.FINISHED) return;
        try {
            TaskStateMachine.transition(t.state(), TaskState.ERROR);
            tasks.updateState(taskId, TaskState.ERROR, t.fullDone(), reason);
        } catch (DtsException ignored) {
            // PAUSED/FINISHED 下的迟到失败回调：保留现场，不改状态
        }
    }

    // ── 内部装配 ──────────────────────────────────────────────────

    private void launchFull(TaskMetadata t) {
        int totalTables = t.tables().size();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        // 多表聚合：所有表都完成才推进状态（单表时退化为直接回调）
        TaskStateNotifier agg = new TaskStateNotifier() {
            @Override
            public void onFullDone(String taskId) {
                if (completed.incrementAndGet() == totalTables) {
                    MigrationEngineImpl.this.onFullDone(taskId);
                }
            }

            @Override
            public void onError(String taskId, String reason) {
                MigrationEngineImpl.this.onError(taskId, reason);
            }
        };
        FullTransferService first = fullServiceFor(t, t.tables().get(0), agg);
        Thread thread = new Thread(() -> {
            try {
                first.run();
                for (int i = 1; i < totalTables; i++) {
                    if (load(t.id()).state() != TaskState.RUNNING_FULL) return; // 被暂停/出错
                    FullTransferService svc = fullServiceFor(t, t.tables().get(i), agg);
                    runtimes.put(t.id(), new Runtime(svc, null));
                    svc.run();
                }
            } catch (Exception e) {
                onError(t.id(), e.getMessage());
            }
        }, "full-" + t.id());
        runtimes.put(t.id(), new Runtime(first, null));
        thread.start();
    }

    private FullTransferService fullServiceFor(TaskMetadata t, String table, TaskStateNotifier notifier) {
        return new FullTransferService(
                t.id(),
                endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()),
                t.schema(), table, t.chunkSize(), t.parallelism(), t.rowsPerSecond(),
                checkpoints, () -> new MySqlSink(
                        Connections.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword())),
                        t.id(), pkMap(t)),
                notifier);
    }

    private void launchIncr(TaskMetadata t, String resumeGtid) {
        BinlogStoreService store = new BinlogStoreService(
                endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()),
                t.schema(), t.tables(), t.id(), this);
        Thread thread = new Thread(() -> store.start(resumeGtid, () -> {
            try {
                return new MySqlSink(
                        Connections.open(endpoint(t.targetUrl(), t.targetUser(), t.targetPassword())),
                        t.id(), pkMap(t));
            } catch (Exception e) {
                throw new DtsException("打开目标连接失败: " + e.getMessage(), e);
            }
        }), "incr-" + t.id());
        runtimes.put(t.id(), new Runtime(null, store));
        thread.start();
    }

    private Map<String, List<String>> pkMap(TaskMetadata t) {
        try (Connection src = Connections.open(endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()))) {
            Map<String, List<String>> map = new LinkedHashMap<>();
            for (String table : t.tables()) {
                map.put(table, PkResolver.resolvePkColumns(src, t.schema(), table));
            }
            return map;
        } catch (Exception e) {
            throw new DtsException("解析主键失败: " + e.getMessage(), e);
        }
    }

    private String readConsistency(TaskMetadata t) {
        try (Connection src = Connections.open(endpoint(t.sourceUrl(), t.sourceUser(), t.sourcePassword()))) {
            return MasterStatusReader.readGtidExecuted(src);
        } catch (Exception e) {
            throw new DtsException("读取一致性位点失败: " + e.getMessage(), e);
        }
    }

    /** 增量恢复点：目标库 dts_incr_checkpoint（R4 同事务保证它 ≤ 已写入数据），无则回退一致性位点。 */
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

    private static DbEndpoint endpoint(String url, String user, String password) {
        return new DbEndpoint(url, user, password);
    }

    private TaskMetadata load(String taskId) {
        return tasks.find(taskId).orElseThrow(() -> new DtsException("任务不存在: " + taskId));
    }

    private static List<String> listAllTables(Connection conn, String schema) throws Exception {
        List<String> names = new ArrayList<>();
        try (var ps = conn.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE='BASE TABLE'")) {
            ps.setString(1, schema);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) names.add(rs.getString(1));
            }
        }
        return names;
    }
}
