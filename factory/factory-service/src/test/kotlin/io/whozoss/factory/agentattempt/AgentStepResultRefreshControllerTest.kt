package io.whozoss.factory.agentattempt

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.domain.ResultCapabilityRefreshForbiddenException
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.web.AgentStepResultRefreshController
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.web.TrustContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentStepResultRefreshControllerTest {
    private val service = mockk<AgentStepResultService>()
    private val scopes = mockk<TenantScopeProvider>()
    private val controller = AgentStepResultRefreshController(service, scopes)

    @Test
    fun `signed service caller refreshes the exact trusted case namespace attempt and agent`() {
        val trust = TrustContext(
            principalId = "actor",
            principalType = TrustContext.PRINCIPAL_TYPE_SERVICE,
            authenticationMethod = TrustContext.AUTH_PROXY_SIGNATURE,
            scopes = listOf("workflow:write"),
            namespaceId = "namespace-1",
            caseId = "case-1",
        )
        val scope = TenantScope("org", "workstream")
        every { scopes.scopeOf(trust) } returns scope
        every { service.refresh(scope, any(), "runtime-1", any(), any(), any()) } returns
            IssuedCapability("new-token", "2030-01-01T00:00:00Z")

        val response = controller.refresh(
            AgentStepResultRefreshController.Request("attempt-1", "runtime-1", "Worker"),
            trust,
        )

        assertThat(response.data.attemptId).isEqualTo("attempt-1")
        assertThat(response.data.capabilityToken).isEqualTo("new-token")
        verify(exactly = 1) {
            service.refresh(
                scope,
                match {
                    it.attemptId == "attempt-1" && it.caseId == "case-1" &&
                        it.namespaceId == "namespace-1" && it.agentName == "Worker"
                },
                "runtime-1",
                "runtime-1|case-1|attempt-1|Worker",
                any(),
                any(),
            )
        }
    }

    @Test
    fun `human or unsigned caller cannot refresh`() {
        val trust = TrustContext(
            principalId = "user",
            principalType = TrustContext.PRINCIPAL_TYPE_HUMAN,
            authenticationMethod = TrustContext.AUTH_JWT,
            scopes = listOf("workflow:write"),
            namespaceId = "namespace-1",
            caseId = "case-1",
        )
        assertThrows(ResultCapabilityRefreshForbiddenException::class.java) {
            controller.refresh(AgentStepResultRefreshController.Request("attempt-1", "runtime-1", "Worker"), trust)
        }
        verify(exactly = 0) { service.refresh(any(), any(), any(), any(), any(), any()) }
    }
}
