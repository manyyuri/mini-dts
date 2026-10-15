package com.minidts.cm;

import com.minidts.common.DtsException;
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
        assertThrows(DtsException.class, () -> TaskStateMachine.transition(TaskState.INIT, TaskState.RUNNING_INCR));
        assertThrows(DtsException.class, () -> TaskStateMachine.transition(TaskState.INIT, TaskState.FINISHED));
        assertThrows(DtsException.class, () -> TaskStateMachine.transition(TaskState.FULL_DONE, TaskState.RUNNING_FULL));
        assertThrows(DtsException.class, () -> TaskStateMachine.transition(TaskState.FINISHED, TaskState.RUNNING_INCR));
        assertThrows(DtsException.class, () -> TaskStateMachine.transition(TaskState.PAUSED, TaskState.FULL_DONE));
        assertThrows(DtsException.class, () -> TaskStateMachine.transition(TaskState.INIT, TaskState.INIT));
    }

    @Test
    void resumeTarget() {
        assertEquals(TaskState.RUNNING_FULL, TaskStateMachine.resumeTo(false));
        assertEquals(TaskState.RUNNING_INCR, TaskStateMachine.resumeTo(true));
        assertTrue(TaskStateMachine.isTerminal(TaskState.FINISHED));
        assertFalse(TaskStateMachine.isTerminal(TaskState.ERROR));
    }
}
