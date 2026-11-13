package io.whozoss.factory.agentattempt.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class DurableAgentAttemptDtoTest {
    @Test
    fun `mandate is absent before dispatch and redacts execution credentials without mutating the frozen brief`() {
        val attempt = DurableAgentAttempt(
            attemptId = "attempt", caseId = "case", namespaceId = "ns", workflowId = "wf", stepId = "step",
            attemptNumber = 1, agentName = "Dev", capabilityToken = "capability-value", ownerToken = "owner-value",
            brief = "User request; capability-value owner-value API_KEY=accidental Bearer credential; attempt",
        )
        assertThat(attempt.toDto().effectiveMandate).isNull()
        val mandate = attempt.copy(startedAt = Instant.now()).toDto().effectiveMandate
        assertThat(mandate).contains("User request", "attempt", "<redacted>")
            .doesNotContain("capability-value", "owner-value", "accidental", "credential")
        assertThat(attempt.brief).contains("capability-value", "owner-value")
    }
}
