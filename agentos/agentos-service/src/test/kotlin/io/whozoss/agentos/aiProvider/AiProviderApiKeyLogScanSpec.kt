package io.whozoss.agentos.aiProvider

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Log-scan test ensuring that raw apiKey values are never written to log output
 * anywhere in the `io.whozoss.agentos` logger hierarchy (NFR-SEC-4).
 *
 * Attaches a Logback [ListAppender] to the root logger of the agentos package, then
 * exercises POST/GET/LIST/PUT/DELETE on the unified `/api/ai-providers` route.
 * After each operation, all captured log messages are scanned for the raw secret
 * string.
 *
 * Migrated from `UserAiProviderApiKeyLogScanSpec` (Decision 19 + Task 5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AiProviderApiKeyLogScanSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var userService: UserService
    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService
    @MockkBean(relaxed = true) lateinit var namespaceService: NamespaceService

    private val aliceId = UUID.randomUUID()
    private val alice = User(
        metadata = EntityMetadata(id = aliceId),
        externalId = "alice@example.com",
        email = "alice@example.com",
        isAdmin = false,
    )
    private val namespaceId = UUID.randomUUID()
    private val ns = Namespace(
        metadata = EntityMetadata(id = namespaceId),
        externalId = "ns-${namespaceId}",
        name = "ns",
    )

    init {
        "apiKey raw value must never appear in log output across all CRUD verbs" {
            every { userService.getCurrentUser() } returns alice
            every { permissionService.hasPermission(any(), any(), any(), any()) } returns false
            every { namespaceService.findById(namespaceId) } returns ns
            every {
                permissionService.hasPermission(aliceId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)
            } returns true

            val secret = "sk-ant-${UUID.randomUUID()}-leak-canary"

            val logCaptor = ListAppender<ILoggingEvent>().also { it.start() }
            val logger = LoggerFactory.getLogger("io.whozoss.agentos") as Logger
            logger.addAppender(logCaptor)

            try {
                // POST — user-global (does not need a namespace permission)
                val postResult = mockMvc.perform(
                    post("/api/ai-providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "userId": "$aliceId", "name": "LOG_SCAN_${UUID.randomUUID()}", "apiType": "Anthropic", "apiKey": "$secret" }"""),
                ).andExpect(status().isCreated)
                    .andReturn()

                val createdId = com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(postResult.response.contentAsString)
                    .get("id")?.asText() ?: error("No id in response")

                // GET
                mockMvc.perform(get("/api/ai-providers/$createdId"))
                    .andExpect(status().isOk)

                // LIST
                mockMvc.perform(get("/api/ai-providers"))
                    .andExpect(status().isOk)

                // PUT with masked apiKey (round-trip)
                mockMvc.perform(
                    put("/api/ai-providers/$createdId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "LOG_SCAN_UPDATED", "apiType": "Anthropic", "apiKey": "sk-a****wxyz" }"""),
                ).andExpect(status().isOk)

                // DELETE
                mockMvc.perform(delete("/api/ai-providers/$createdId"))
                    .andExpect(status().isNoContent)
            } finally {
                logger.detachAppender(logCaptor)
            }

            logCaptor.list.forEach { event ->
                withClue("Log message '${event.formattedMessage}' must not contain raw apiKey") {
                    event.formattedMessage.contains(secret) shouldBe false
                }
                event.argumentArray?.forEach { arg ->
                    withClue("Log argument '${arg}' must not contain raw apiKey") {
                        arg.toString().contains(secret) shouldBe false
                    }
                }
            }
        }
    }
}
