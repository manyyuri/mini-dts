package com.minidts.cm;

import com.minidts.common.DtsException;

import java.util.Map;
import java.util.Set;

/**
 * 纯状态机（无 IO，可穷举测试）。
 *
 * INIT → RUNNING_FULL → FULL_DONE → RUNNING_INCR → FINISHED
 *   任意运行态 → PAUSED / ERROR；PAUSED/ERROR → 按记录的阶段恢复。
 * FINISHED 为终态（校验 verify 是旁路动作，不改状态）。
 */
public final class TaskStateMachine {

    private static final Map<TaskState, Set<TaskState>> ALLOWED = Map.of(
            TaskState.INIT, Set.of(TaskState.RUNNING_FULL),
            TaskState.RUNNING_FULL, Set.of(TaskState.FULL_DONE, TaskState.PAUSED, TaskState.ERROR),
            TaskState.FULL_DONE, Set.of(TaskState.RUNNING_INCR, TaskState.PAUSED, TaskState.ERROR),
            TaskState.RUNNING_INCR, Set.of(TaskState.FINISHED, TaskState.PAUSED, TaskState.ERROR),
            TaskState.PAUSED, Set.of(TaskState.RUNNING_FULL, TaskState.RUNNING_INCR),
            TaskState.ERROR, Set.of(TaskState.RUNNING_FULL, TaskState.RUNNING_INCR),
            TaskState.FINISHED, Set.of());

    /**
     * 同态重挂：进程重启 / 节点接管时，DB 里记录的运行态就是目标态，此时"迁移到自己"是合法动作。
     * 与 {@link #transition} 分开，是为了不放松正常流转的校验（自迁移在任何业务动作里都没有意义）。
     */
    public static void reattach(TaskState from, TaskState to) {
        if (from == to) {
            return;
        }
        transition(from, to);
    }

    public static void transition(TaskState from, TaskState to) {
        if (from == to) {
            throw new DtsException("状态自迁移无意义: " + from);
        }
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new DtsException("非法状态迁移: " + from + " → " + to
                    + "（允许: " + ALLOWED.get(from) + "）");
        }
    }

    /** 暂停/出错后恢复：回到哪个运行态。 */
    public static TaskState resumeTo(boolean fullDone) {
        return fullDone ? TaskState.RUNNING_INCR : TaskState.RUNNING_FULL;
    }

    public static boolean isTerminal(TaskState s) {
        return s == TaskState.FINISHED;
    }
}
