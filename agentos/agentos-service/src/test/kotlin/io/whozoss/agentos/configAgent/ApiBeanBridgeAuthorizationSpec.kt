package io.whozoss.agentos.configAgent

import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigController
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigApi
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Proves the assumption the config agent's phase 1 rests on (#1477): a server endpoint that writes
 * through the injected `*Api` beans, inside the HTTP request of the user who clicked Apply, is
 * authorized exactly like the REST call the browser would have made.
 *
 * - The `*Api` bean is the controller's method-security proxy, so its `@PreAuthorize` fires on an
 *   in-process call.
 * - The identity checked is the one of the current request, both by `@PreAuthorize` and by the
 *   inline guards that read [UserService.getCurrentUser] (`requireSuperAdmin`).
 * - Outside a request (an agent tool runs in a detached coroutine), the same call is refused before
 *   the controller runs.
 *
 * The full chain is real: [io.whozoss.agentos.security.declarative.AgentOsAuthenticationFilter] →
 * [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator] → [PermissionService] →
 * Neo4j. Only [UserService] is mocked, to choose the current user, as in
 * `io.whozoss.agentos.scheduledPrompt.ScheduledPromptControllerPermissionIntegrationSpec`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ApiBeanBridgeAuthorizationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var configWriter: ConfigWriter
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var agentConfigApi: AgentConfigApi
    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository
    @Autowired lateinit var integrationConfigService: IntegrationConfigService
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository

    @MockkBean(relaxed = true) lateinit var userService: UserService

    private lateinit var alice: User // non-admin, ADMIN of the namespace
    private lateinit var bob: User // non-admin, no permission
    private lateinit var root: User // super-admin
    private lateinit var namespace: Namespace
    private lateinit var agent: AgentConfig

    private fun user(isAdmin: Boolean): User {
        val id = UUID.randomUUID()
        return User(
            metadata = EntityMetadata(id = id),
            externalId = "bridge-$id@example.com",
            email = "bridge-$id@example.com",
            isAdmin = isAdmin,
        )
    }

    private fun actAs(user: User) {
        every { userService.getCurrentUser() } returns user
    }

    init {
        beforeEach {
            alice = user(isAdmin = false)
            bob = user(isAdmin = false)
            root = user(isAdmin = true)
            listOf(alice, bob, root).forEach { u ->
                userRepository.save(u)
                every { userService.findById(u.id) } returns u
            }
            namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        externalId = "bridge-ns-${UUID.randomUUID()}",
                        name = "Bridge Namespace",
                    ),
                )
            permissionService.grantPermission(
                alice.id.toString(),
                EntityType.NAMESPACE,
                namespace.id.toString(),
                PermissionRelation.ADMIN,
            )
            agent =
                agentConfigRepository.save(
                    AgentConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespace.id,
                        name = "bridge-agent-${UUID.randomUUID()}",
                        enabled = true,
                    ),
                )
        }

        "the injected AgentConfigApi is the method-security proxy of the controller" {
            AopUtils.isAopProxy(agentConfigApi) shouldBe true
            AopUtils.getTargetClass(agentConfigApi) shouldBe AgentConfigController::class.java
        }

        "a caller without AgentConfig WRITE is refused by the Api bean, and nothing is written" {
            actAs(bob)

            mockMvc
                .perform(
                    put("/__test/api-bean-bridge/agent-configs/${agent.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "renamed-by-bob" }"""),
                ).andExpect(status().isForbidden)

            agentConfigService.findById(agent.id)!!.name shouldBe agent.name
        }

        "a namespace admin is allowed through the Api bean, with the identity of the request" {
            actAs(alice)

            mockMvc
                .perform(
                    put("/__test/api-bean-bridge/agent-configs/${agent.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "name": "renamed-by-alice" }"""),
                ).andExpect(status().isOk)

            agentConfigService.findById(agent.id)!!.name shouldBe "renamed-by-alice"
        }

        "an inline controller guard also reads the identity of the request" {
            val name = "BRIDGE_PLATFORM_${UUID.randomUUID()}"
            val platformPayload = """{ "name": "$name", "integrationType": "JIRA" }"""

            actAs(bob)
            mockMvc
                .perform(
                    post("/__test/api-bean-bridge/integration-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(platformPayload),
                ).andExpect(status().isForbidden)
            integrationConfigService.findPlatform().none { it.name == name } shouldBe true

            actAs(root)
            mockMvc
                .perform(
                    post("/__test/api-bean-bridge/integration-configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(platformPayload),
                ).andExpect(status().isCreated)
        }

        "outside an HTTP request, the Api bean refuses before the controller runs" {
            SecurityContextHolder.clearContext()
            actAs(alice)

            shouldThrow<AuthenticationCredentialsNotFoundException> {
                agentConfigApi.update(agent.id, AgentConfigDto(name = "renamed-outside-request"))
            }

            agentConfigService.findById(agent.id)!!.name shouldBe agent.name
        }

        "outside an HTTP request, the writer refuses with a clear message before reaching any Api bean" {
            SecurityContextHolder.clearContext()
            actAs(alice)

            val refused = shouldThrow<IllegalStateException> { configWriter.setEnabled(agent.id, enabled = false) }

            refused.message shouldBe "ConfigWriter must run inside the HTTP request of the user who applies the plan"
            agentConfigService.findById(agent.id)!!.enabled shouldBe true
        }
    }
}

/**
 * Test-only stand-in for the future `apply` endpoint: one HTTP request that writes through the
 * injected `*Api` beans, the way `ApiBeanConfigWriter` will. Served under `/__test/`, like
 * [io.whozoss.agentos.security.declarative.SecurityTestController].
 */
@RestController
@RequestMapping("/__test/api-bean-bridge")
class ApiBeanBridgeTestController(
    private val agentConfigApi: AgentConfigApi,
    private val integrationConfigApi: IntegrationConfigApi,
) {
    @PutMapping("/agent-configs/{id}")
    fun updateAgent(
        @PathVariable id: UUID,
        @RequestBody resource: AgentConfigDto,
    ): AgentConfigDto = agentConfigApi.update(id, resource)

    @PostMapping("/integration-configs")
    @ResponseStatus(HttpStatus.CREATED)
    fun createIntegration(
        @RequestBody resource: IntegrationConfigDto,
    ): IntegrationConfigDto = integrationConfigApi.create(resource)
}
