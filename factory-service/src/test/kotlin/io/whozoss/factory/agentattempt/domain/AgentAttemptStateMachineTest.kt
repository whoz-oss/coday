package io.whozoss.factory.agentattempt.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit test of the durable execution attempt state machine.
 *
 * The safety invariant under test: a timeout, an incomplete result or an
 * unknown state can never yield `succeeded` — [AgentAttemptStatus.SUCCEEDED] is
 * reachable only from `running` and `waiting_human`.
 */
class AgentAttemptStateMachineTest {

    @Test
    fun `every allowed transition is accepted by canTransitionTo`() {
        AgentAttemptStatus.transitions().forEach { (from, targets) ->
            targets.forEach { to ->
                assertThat(from.canTransitionTo(to))
                    .describedAs("$from -> $to")
                    .isTrue()
            }
        }
    }

    /**
     * Req 2 attestation: the transition set is imposed — every `(from, to)`
     * pair NOT listed in `ALLOWED_TRANSITIONS` is rejected by
     * [AgentAttemptStatus.canTransitionTo]. This covers the required chain
     * `pending -> starting -> running -> waiting_human -> succeeded|failed|
     * indeterminate|interrupted` (with the extra `claiming` hop the real
     * machine inserts between `pending` and `starting`).
     */
    @Test
    fun `every illegal transition is rejected by canTransitionTo`() {
        AgentAttemptStatus.entries.forEach { from ->
            val allowed = AgentAttemptStatus.transitions().getValue(from)
            AgentAttemptStatus.entries.filter { it !in allowed }.forEach { to ->
                assertThat(from.canTransitionTo(to))
                    .describedAs("$from -> $to must be rejected")
                    .isFalse()
            }
        }
    }

    @Test
    fun `succeeded is reachable only from running and waiting_human`() {
        val allowedSources = setOf(AgentAttemptStatus.RUNNING, AgentAttemptStatus.WAITING_HUMAN)
        AgentAttemptStatus.entries.forEach { from ->
            assertThat(from.canTransitionTo(AgentAttemptStatus.SUCCEEDED))
                .describedAs("$from -> SUCCEEDED")
                .isEqualTo(from in allowedSources)
        }
    }

    @Test
    fun `terminal states allow no outgoing transition`() {
        listOf(
            AgentAttemptStatus.SUCCEEDED,
            AgentAttemptStatus.FAILED,
            AgentAttemptStatus.INDETERMINATE,
            AgentAttemptStatus.INTERRUPTED,
            AgentAttemptStatus.SUPERSEDED,
        ).forEach { terminal ->
            assertThat(terminal.terminal).isTrue()
            AgentAttemptStatus.entries.forEach { to ->
                assertThat(terminal.canTransitionTo(to))
                    .describedAs("$terminal -> $to")
                    .isFalse()
            }
        }
    }

    @Test
    fun `only succeeded is a success and non-terminal states are not terminal`() {
        AgentAttemptStatus.entries.forEach { status ->
            assertThat(status.isSuccess).isEqualTo(status == AgentAttemptStatus.SUCCEEDED)
        }
        listOf(
            AgentAttemptStatus.PENDING,
            AgentAttemptStatus.CLAIMING,
            AgentAttemptStatus.STARTING,
            AgentAttemptStatus.RUNNING,
            AgentAttemptStatus.WAITING_HUMAN,
        ).forEach { status ->
            assertThat(status.terminal).isFalse()
        }
    }

    /**
     * Phase 4 ask-step-question attestation: `superseded` is reachable ONLY
     * from `waiting_human` (a parked attempt whose human answer was received),
     * it is terminal, and it is never a success.
     */
    @Test
    fun `superseded is terminal, not a success, and reachable only from waiting_human`() {
        AgentAttemptStatus.entries.forEach { from ->
            assertThat(from.canTransitionTo(AgentAttemptStatus.SUPERSEDED))
                .describedAs("$from -> SUPERSEDED")
                .isEqualTo(from == AgentAttemptStatus.WAITING_HUMAN)
        }
        assertThat(AgentAttemptStatus.SUPERSEDED.terminal).isTrue()
        assertThat(AgentAttemptStatus.SUPERSEDED.isSuccess).isFalse()
        assertThat(AgentAttemptStatus.SUPERSEDED.canTransitionTo(AgentAttemptStatus.RUNNING)).isFalse()
        assertThat(AgentAttemptStatus.SUPERSEDED.canTransitionTo(AgentAttemptStatus.PENDING)).isFalse()
        assertThat(AgentAttemptStatus.SUPERSEDED.dbValue).isEqualTo("superseded")
    }

    @Test
    fun `db values round-trip through fromDbValue`() {
        AgentAttemptStatus.entries.forEach { status ->
            assertThat(AgentAttemptStatus.fromDbValue(status.dbValue)).isEqualTo(status)
        }
    }
}
