package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.toDto
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Req 8 attestation: the durable attempt carries the link to the work
 * environment it ran against — `environmentRef` (the environment id) and
 * `expectedEnvironmentRevision` (the environment optimistic-lock revision
 * captured at reservation) — preserved unchanged across the whole lifecycle
 * and surfaced on the bounded public read model.
 */
class DurableAgentAttemptEnvironmentLinkTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var service: DurableAgentAttemptService

    @Test
    fun `the environment link captured at reservation survives the whole lifecycle and reaches the dto`() {
        service.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-env",
                caseId = "case-1",
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptNumber = 1,
                agentName = "builder",
                environmentRef = "env-1",
                expectedEnvironmentRevision = 3,
            ),
        )
        service.claim(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-env",
            ownerToken = "owner-a",
            leaseTtlMs = 60_000,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-env",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.STARTING,
        )
        service.transition(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-env",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.RUNNING,
        )
        service.finalize(
            scope,
            namespaceId = NAMESPACE_ID,
            workflowId = WORKFLOW_ID,
            stepId = STEP_ID,
            attemptId = "attempt-env",
            ownerToken = "owner-a",
            target = AgentAttemptStatus.SUCCEEDED,
        )

        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-env")
        assertThat(persisted!!.status).isEqualTo(AgentAttemptStatus.SUCCEEDED)
        assertThat(persisted.environmentRef).isEqualTo("env-1")
        assertThat(persisted.expectedEnvironmentRevision).isEqualTo(3)

        val dto = persisted.toDto()
        assertThat(dto.environmentRef).isEqualTo("env-1")
        assertThat(dto.expectedEnvironmentRevision).isEqualTo(3)
    }

    @Test
    fun `an attempt reserved without an environment keeps a null link`() {
        service.register(
            scope,
            DurableAgentAttempt(
                attemptId = "attempt-no-env",
                caseId = "case-1",
                namespaceId = NAMESPACE_ID,
                workflowId = WORKFLOW_ID,
                stepId = STEP_ID,
                attemptNumber = 1,
                agentName = "builder",
            ),
        )

        val persisted = service.find(scope, NAMESPACE_ID, WORKFLOW_ID, STEP_ID, "attempt-no-env")
        assertThat(persisted!!.environmentRef).isNull()
        assertThat(persisted.expectedEnvironmentRevision).isNull()
        assertThat(persisted.toDto().environmentRef).isNull()
    }

    companion object {
        private const val NAMESPACE_ID = "ns-env"
        private const val WORKFLOW_ID = "wf-env"
        private const val STEP_ID = "step-env"
    }
}
