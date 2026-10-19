package com.minioms.cm;

import com.minioms.common.OmsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 穷举状态迁移合法性。 */
class TaskStateMachineTest {

    @Test
    void happyPath() {
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.INIT, TaskState.RUNNING_FULL));
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.RUNNING_FULL, TaskState.FULL_DONE));
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.FULL_DONE, TaskState.RUNNING_INCR));
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.RUNNING_INCR, TaskState.FINISHED));
    }

    @Test
    void pauseAndErrorFromRunningStates() {
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.RUNNING_FULL, TaskState.PAUSED));
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.RUNNING_INCR, TaskState.ERROR));
        assertDoesNotThrow(() -> TaskStateMachine.transition(TaskState.RUNNING_FULL, TaskState.ERROR));
    }

    @Test
    void illegalTransitionsRejected() {
        assertThrows(OmsException.class, () -> TaskStateMachine.transition(TaskState.INIT, TaskState.RUNNING_INCR));
        assertThrows(OmsException.class, () -> TaskStateMachine.transition(TaskState.INIT, TaskState.FINISHED));
        assertThrows(OmsException.class, () -> TaskStateMachine.transition(TaskState.FULL_DONE, TaskState.RUNNING_FULL));
        assertThrows(OmsException.class, () -> TaskStateMachine.transition(TaskState.FINISHED, TaskState.RUNNING_INCR));
        assertThrows(OmsException.class, () -> TaskStateMachine.transition(TaskState.PAUSED, TaskState.FULL_DONE));
        assertThrows(OmsException.class, () -> TaskStateMachine.transition(TaskState.INIT, TaskState.INIT));
    }

    @Test
    void reattachAllowsSameStateForCrashRecovery() {
        // 崩溃/接管后 DB 里记的就是运行态：允许“迁移到自己”
        assertDoesNotThrow(() -> TaskStateMachine.reattach(TaskState.RUNNING_INCR, TaskState.RUNNING_INCR));
        assertDoesNotThrow(() -> TaskStateMachine.reattach(TaskState.RUNNING_FULL, TaskState.RUNNING_FULL));
        assertDoesNotThrow(() -> TaskStateMachine.reattach(TaskState.PAUSED, TaskState.RUNNING_INCR));
        assertThrows(OmsException.class, () -> TaskStateMachine.reattach(TaskState.FINISHED, TaskState.RUNNING_INCR));
        assertThrows(OmsException.class, () -> TaskStateMachine.reattach(TaskState.INIT, TaskState.RUNNING_INCR));
    }

    @Test
    void resumeTarget() {
        assertEquals(TaskState.RUNNING_FULL, TaskStateMachine.resumeTo(false));
        assertEquals(TaskState.RUNNING_INCR, TaskStateMachine.resumeTo(true));
        assertTrue(TaskStateMachine.isTerminal(TaskState.FINISHED));
        assertFalse(TaskStateMachine.isTerminal(TaskState.ERROR));
    }
}
