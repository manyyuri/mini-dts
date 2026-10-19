package com.minioms.starter;

import com.minioms.cm.TaskMetadata;
import com.minioms.cm.TaskRepository;
import com.minioms.cm.TaskState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.logging.Logger;

/**
 * 启动恢复 + 租约心跳。
 *
 * 老实现的重启语义是「任务卡在 RUNNING_*，线程没了，没人知道」：
 * 状态说在跑、实际不同步、指标从此不动。这里补上两件必须的事：
 *  - 启动时把 DB 里声明在跑的任务重新挂起来（全量从目标库断点、增量从目标库位点）
 *  - 定时续租；续不上说明被别的节点接管，立刻停掉本地运行时（防双跑）
 */
public class TaskRecovery {

    private static final Logger LOG = Logger.getLogger(TaskRecovery.class.getName());

    private final MigrationEngineImpl engine;
    private final TaskRepository tasks;
    private final EngineProperties props;

    public TaskRecovery(MigrationEngineImpl engine, TaskRepository tasks, EngineProperties props) {
        this.engine = engine;
        this.tasks = tasks;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!props.recoverOnStartup()) {
            return;
        }
        List<TaskMetadata> running = tasks.findByStates(TaskState.RUNNING_FULL, TaskState.RUNNING_INCR);
        for (TaskMetadata t : running) {
            boolean taken = engine.reattach(t.id());
            LOG.info("启动恢复 " + t.id() + "(" + t.state() + "): " + (taken ? "已接管" : "跳过（租约在别处或已达并发上限）"));
        }
    }

    @Scheduled(fixedDelayString = "${minioms.lease-renew-interval-millis:10000}")
    public void heartbeat() {
        engine.renewLeases();
    }
}
