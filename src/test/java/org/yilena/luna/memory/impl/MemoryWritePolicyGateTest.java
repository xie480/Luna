package org.yilena.luna.memory.impl;

import org.junit.jupiter.api.Test;
import org.yilena.luna.enums.TaskRuntimeState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MemoryWritePolicyGateTest {

    @Test
    void shouldHardDenyPendingOrIntermediateLongTermWrites() {
        MemoryWritePolicyGate gate = new MemoryWritePolicyGate();
        MemoryWritePolicyGate.GateContext context = new MemoryWritePolicyGate.GateContext(
                "s1",
                TaskRuntimeState.EXECUTING,
                0.9,
                0.9
        );

        MemoryWritePolicyGate.GateDecision sourceDenied = gate.evaluateLongTermWrite(
                context,
                "TASK_SEMANTIC",
                "PENDING_TOOL_RESULT",
                0.9,
                "verified fact"
        );
        MemoryWritePolicyGate.GateDecision contentDenied = gate.evaluateLongTermWrite(
                context,
                "TASK_SEMANTIC",
                "SUMMARY_SNAPSHOT",
                0.9,
                "intermediate hypothesis, pending tool verification"
        );

        assertFalse(sourceDenied.allow());
        assertEquals("HARD_DENY_INTERMEDIATE_OR_PENDING", sourceDenied.reasonCode());
        assertFalse(contentDenied.allow());
        assertEquals("HARD_DENY_INTERMEDIATE_OR_PENDING", contentDenied.reasonCode());
    }
}

