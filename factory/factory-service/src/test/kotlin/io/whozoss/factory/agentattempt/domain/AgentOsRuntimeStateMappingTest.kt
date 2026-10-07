package io.whozoss.factory.agentattempt.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Phase 10 pure-domain attestation of the AgentOS runtime-state / Factory
 * verdict distinction of
 * `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/AgentOsRuntimeStateMapping.kt`
 * and of the observability fields appended to
 * `factory-service/src/main/kotlin/io/whozoss/factory/agentattempt/domain/DurableAgentAttemptDto.kt`.
 *
 * Core rule attested here: no AgentOS runtime state (quiescent, terminal,
 * archived, closed-by-user, or plain silence) EVER maps to
 * [AgentOsRuntimeStateMapping.SealingClass.COMPLETED] — only the authoritative
 * Factory verdict `succeeded` (sealed via the capability-backed result
 * channel) does. "No success by silence."
 */
class AgentOsRuntimeStateMappingTest {

    @Test
    fun `every durable attempt status maps to its sealing class`() {
        val expected = mapOf(
            AgentAttemptStatus.PENDING to AgentOsRuntimeStateMapping.SealingClass.ACTIVE,
            AgentAttemptStatus.CLAIMING to AgentOsRuntimeStateMapping.SealingClass.ACTIVE,
            AgentAttemptStatus.STARTING to AgentOsRuntimeStateMapping.SealingClass.ACTIVE,
            AgentAttemptStatus.RUNNING to AgentOsRuntimeStateMapping.SealingClass.ACTIVE,
            AgentAttemptStatus.WAITING_HUMAN to AgentOsRuntimeStateMapping.SealingClass.ACTIVE,
            AgentAttemptStatus.SUCCEEDED to AgentOsRuntimeStateMapping.SealingClass.COMPLETED,
            AgentAttemptStatus.FAILED to AgentOsRuntimeStateMapping.SealingClass.RUNTIME_CLOSED,
            AgentAttemptStatus.INDETERMINATE to AgentOsRuntimeStateMapping.SealingClass.RUNTIME_CLOSED,
            AgentAttemptStatus.INTERRUPTED to AgentOsRuntimeStateMapping.SealingClass.RUNTIME_CLOSED,
            AgentAttemptStatus.SUPERSEDED to AgentOsRuntimeStateMapping.SealingClass.RUNTIME_CLOSED,
        )
        assertThat(AgentAttemptStatus.entries.toSet()).isEqualTo(expected.keys)
        expected.forEach { (status, sealingClass) ->
            assertThat(AgentOsRuntimeStateMapping.classify(status))
                .describedAs("classify(%s)", status)
                .isEqualTo(sealingClass)
        }
    }

    @Test
    fun `no agentos runtime state ever seals a completed verdict`() {
        // The runtime vocabulary is exposed as import-free constants only.
        assertThat(AgentOsRuntimeStateMapping.RUNTIME_TERMINAL_STATES)
            .containsExactlyInAnyOrder(AgentOsRuntimeStateMapping.KILLED, AgentOsRuntimeStateMapping.ERROR)
        listOf(
            AgentOsRuntimeStateMapping.PENDING,
            AgentOsRuntimeStateMapping.RUNNING,
            AgentOsRuntimeStateMapping.IDLE,
            AgentOsRuntimeStateMapping.KILLED,
            AgentOsRuntimeStateMapping.ERROR,
            AgentOsRuntimeStateMapping.ARCHIVED,
            AgentOsRuntimeStateMapping.CLOSED_BY_USER,
        ).forEach { runtimeState ->
            // A runtime state is an observation axis, never a Factory verdict:
            // the ONLY status classified COMPLETED is the authoritative
            // capability-backed `succeeded` — never silence, quiescence or a
            // terminal runtime state.
            assertThat(runtimeState).isNotEqualTo(AgentAttemptStatus.SUCCEEDED.dbValue)
        }
        assertThat(AgentAttemptStatus.entries.filter { AgentOsRuntimeStateMapping.classify(it) == AgentOsRuntimeStateMapping.SealingClass.COMPLETED })
            .containsExactly(AgentAttemptStatus.SUCCEEDED)
        // ARCHIVED is a consumer label only: no attempt status maps to it.
        assertThat(AgentAttemptStatus.entries.filter { AgentOsRuntimeStateMapping.classify(it) == AgentOsRuntimeStateMapping.SealingClass.ARCHIVED })
            .isEmpty()
    }

    @Test
    fun `the dto exposes the terminal flag and the sealing class of the verdict`() {
        val running = attempt(AgentAttemptStatus.RUNNING).toDto()
        assertThat(running.terminal).isFalse()
        assertThat(running.sealingClass).isEqualTo("ACTIVE")

        val succeeded = attempt(AgentAttemptStatus.SUCCEEDED).toDto()
        assertThat(succeeded.terminal).isTrue()
        assertThat(succeeded.sealingClass).isEqualTo("COMPLETED")

        val indeterminate = attempt(AgentAttemptStatus.INDETERMINATE).toDto()
        assertThat(indeterminate.terminal).isTrue()
        assertThat(indeterminate.sealingClass).isEqualTo("RUNTIME_CLOSED")

        AgentAttemptStatus.entries.forEach { status ->
            val dto = attempt(status).toDto()
            assertThat(dto.terminal).isEqualTo(status.terminal)
            assertThat(dto.sealingClass).isEqualTo(AgentOsRuntimeStateMapping.classify(status).name)
        }
    }

    private fun attempt(status: AgentAttemptStatus): DurableAgentAttempt = DurableAgentAttempt(
        attemptId = "attempt-mapping",
        caseId = "case-1",
        namespaceId = "ns-mapping",
        workflowId = "wf-mapping",
        stepId = "step-mapping",
        attemptNumber = 1,
        agentName = "builder",
        status = status,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
