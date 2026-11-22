package io.whozoss.agentos.caseFlow

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.mockk.every
import io.whozoss.agentos.agent.LastLlmRequest
import io.whozoss.agentos.agent.LastLlmRequestRegistry
import io.whozoss.agentos.chat.LlmRequestOptions
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID

/**
 * `GET /api/cases/{caseId}/prompt-cache` end to end (security filter, `@PreAuthorize`, the registry bean): READ on
 * the case is required, as for its usage. The permission decision is mocked, as in
 * [CaseKeepAliveEndpointIntegrationSpec].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CasePromptCacheEndpointIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var registry: LastLlmRequestRegistry

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    private val member =
        User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "prompt-cache@example.com",
            email = "prompt-cache@example.com",
            isAdmin = false,
        )

    private fun canRead(
        caseId: UUID,
        granted: Boolean,
    ) {
        every { permissionService.hasPermission(member.id.toString(), EntityType.CASE, caseId.toString(), Action.READ) } returns granted
    }

    /** The case's last request: a Claude model via Requesty, of an unknown model (so no pricing). */
    private fun recordLastRequest(caseId: UUID): LastLlmRequest {
        registry.put(
            LastLlmRequest(
                caseId = caseId,
                namespaceId = UUID.randomUUID(),
                userId = member.id,
                messages = listOf(UserMessage("question")),
                toolDefinitions = emptyList(),
                modelId = UUID.randomUUID(),
                providerId = UUID.randomUUID(),
                requestOptions =
                    LlmRequestOptions(AiApiType.OpenAI, "https://router.requesty.ai/v1", "anthropic/claude-sonnet-4-5", null, null, null, null, "digest"),
                finalText = "Done",
                agentName = "planner",
                agentConfigId = null,
                coveredEventCount = 2,
                lastCoveredEventId = UUID.randomUUID(),
                prefixTokens = 100_000,
            ),
            registry.invalidate(caseId),
        )
        return registry.get(caseId)!!
    }

    private fun getStatus(caseId: UUID) = mockMvc.perform(get("/api/cases/$caseId/prompt-cache"))

    init {
        beforeEach { every { userService.getCurrentUser() } returns member }

        "with READ on the case, returns when its cache expires, without a cost for an unpriced model" {
            val caseId = UUID.randomUUID()
            canRead(caseId, granted = true)
            val request = recordLastRequest(caseId)

            getStatus(caseId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.expiresAt").value(request.lastUsedAt.plus(Duration.ofMinutes(5)).toString()))
                .andExpect(jsonPath("$.coldCostEstimate").doesNotExist())
        }

        "a case without a last request has no known status" {
            val caseId = UUID.randomUUID()
            canRead(caseId, granted = true)

            getStatus(caseId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.coldCostEstimate").doesNotExist())
        }

        "without READ on the case, the status is refused with a 403" {
            val caseId = UUID.randomUUID()
            canRead(caseId, granted = false)
            recordLastRequest(caseId)

            getStatus(caseId).andExpect(status().isForbidden)
        }
    }
}
