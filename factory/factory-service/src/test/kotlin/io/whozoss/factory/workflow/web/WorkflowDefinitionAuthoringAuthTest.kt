package io.whozoss.factory.workflow.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.persistence.OrganizationScope
import io.whozoss.factory.web.AuthenticatedUserArgumentResolver
import io.whozoss.factory.web.AuthenticationFilter
import io.whozoss.factory.web.FakeIdp
import io.whozoss.factory.web.JwtTokenVerifier
import io.whozoss.factory.web.MembershipResolver
import io.whozoss.factory.web.security.AuthIdentityResolver
import io.whozoss.factory.workflow.persistence.WorkflowDefinitionCreator
import io.whozoss.factory.workflow.service.WorkflowService
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** Real auth filter and registry HTTP mapping; membership is trusted server data, never model input. */
class WorkflowDefinitionAuthoringAuthTest {
    private val secret = "authoring-auth-unit-secret"
    private val mapper = ObjectMapper()
    private val service = mockk<WorkflowService>()
    private val creator = mockk<WorkflowDefinitionCreator>()
    private val membership = object : MembershipResolver {
        override fun resolveOrganizationId(userId: String): String = "org-$userId"
    }
    private val filter = AuthenticationFilter(
        AuthIdentityResolver(
            JwtTokenVerifier(FactoryProperties(security = FactoryProperties.Security(fakeIdpSecret = secret))),
            mapper,
        ),
        membership,
    )
    private val mvc = MockMvcBuilders.standaloneSetup(WorkflowDefinitionController(service, mapper, creator))
        .setCustomArgumentResolvers(AuthenticatedUserArgumentResolver())
        .addFilters<StandaloneMockMvcBuilder>(filter)
        .build()
    private val definition = """
        {"schemaVersion":"1","workflowType":"auth-test","version":"1.0.0","title":"Auth test",
         "steps":[{"id":"gate","name":"Gate","responsibility":{"kind":"human","name":"reviewer"},"dependsOn":[]}]}
    """.trimIndent()

    @Test
    fun `result capability and missing or invalid credentials cannot validate or register`() {
        // Result capabilities are opaque bearer strings, not Factory operator JWTs.
        listOf(null, "attempt-result-capability", "tampered.jwt.signature").forEach { token ->
            listOf("", "/validate").forEach { suffix ->
                val request = post("/api/factory/workflow-definitions$suffix")
                    .contentType(MediaType.APPLICATION_JSON).content(definition)
                if (token != null) request.header("Authorization", "Bearer $token")
                mvc.perform(request).andExpect(status().isUnauthorized)
            }
        }
        verify(exactly = 0) { creator.createIfAbsent(any(), any()) }
    }

    @Test
    fun `operator registration takes organization only from authenticated membership`() {
        listOf("alice", "bob").forEach { user ->
            every { creator.createIfAbsent(OrganizationScope("org-$user"), any()) } returns true
            val token = FakeIdp.issueJwt(mapOf("sub" to user), secret)
            mvc.perform(
                post("/api/factory/workflow-definitions")
                    .header("Authorization", "Bearer $token")
                    .header("X-Organization-Id", "org-victim")
                    .header("X-Workstream-Id", "ws-victim")
                    .contentType(MediaType.APPLICATION_JSON).content(definition),
            ).andExpect(status().isCreated)
            verify(exactly = 1) { creator.createIfAbsent(OrganizationScope("org-$user"), any()) }
        }
        verify(exactly = 0) { creator.createIfAbsent(OrganizationScope("org-victim"), any()) }
    }
}
