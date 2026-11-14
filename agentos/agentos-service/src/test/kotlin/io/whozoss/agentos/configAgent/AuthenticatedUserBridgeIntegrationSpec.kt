package io.whozoss.agentos.configAgent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigApi
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import io.whozoss.agentos.sdk.api.namespace.NamespaceApi
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.AgentOsAuthentication
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.context.request.RequestContextHolder
import java.util.UUID

/**
 * The other half of [ApiBeanBridgeAuthorizationSpec]: outside any HTTP request, with a user authenticated
 * on the thread, as a messaging chat acts for its linked user, the `*Api` beans and [ConfigWriter] authorize
 * that user exactly like the browser's request.
 *
 * Nothing is mocked: the identity the inline guards read comes from the real [UserService.getCurrentUser],
 * which must return the authenticated user rather than resolve one from the (missing) request or from the
 * OS account the server runs under.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AuthenticatedUserBridgeIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var configWriter: ConfigWriter
    @Autowired lateinit var agentConfigApi: AgentConfigApi
    @Autowired lateinit var namespaceApi: NamespaceApi
    @Autowired lateinit var agentConfigService: AgentConfigService
    @Autowired lateinit var agentConfigRepository: AgentConfigRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var permissionService: PermissionService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var userService: UserService

    private lateinit var alice: User // non-admin, ADMIN of the namespace
    private lateinit var bob: User // non-admin, no permission
    private lateinit var root: User // super-admin
    private lateinit var namespace: Namespace
    private lateinit var agent: AgentConfig

    private fun user(isAdmin: Boolean): User {
        val id = UUID.randomUUID()
        return userRepository.save(
            User(
                metadata = EntityMetadata(id = id),
                externalId = "authenticated-$id@example.com",
                email = "authenticated-$id@example.com",
                isAdmin = isAdmin,
            ),
        )
    }

    private fun <T> authenticatedAs(
        user: User,
        block: () -> T,
    ): T {
        SecurityContextHolder.getContext().authentication = AgentOsAuthentication(user)
        return try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    init {
        beforeEach {
            RequestContextHolder.resetRequestAttributes()
            alice = user(isAdmin = false)
            bob = user(isAdmin = false)
            root = user(isAdmin = true)
            namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        externalId = "authenticated-ns-${UUID.randomUUID()}",
                        name = "Authenticated Namespace",
                    ),
                )
            permissionService.grantPermission(alice.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), PermissionRelation.ADMIN)
            agent =
                agentConfigRepository.save(
                    AgentConfig(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespace.id,
                        name = "authenticated-agent-${UUID.randomUUID()}",
                        enabled = true,
                    ),
                )
        }

        "the current user is the authenticated one, outside any request" {
            authenticatedAs(alice) { userService.getCurrentUser().id } shouldBe alice.id
        }

        "an Api bean refuses an authenticated user without the right, and allows one with it" {
            shouldThrow<AccessDeniedException> {
                authenticatedAs(bob) { agentConfigApi.update(agent.id, AgentConfigDto(name = "renamed-by-bob")) }
            }
            agentConfigService.findById(agent.id)!!.name shouldBe agent.name

            authenticatedAs(alice) { agentConfigApi.update(agent.id, AgentConfigDto(name = "renamed-by-alice")) }
            agentConfigService.findById(agent.id)!!.name shouldBe "renamed-by-alice"
        }

        "an inline guard reads the authenticated user too, not the account the server runs under" {
            // Namespace WRITE passes the @PreAuthorize; only a super-admin passes the guard on the shared path.
            val current = authenticatedAs(alice) { namespaceApi.getById(namespace.id) }
            val moved = current.copy(sharedExchangePath = "/srv/agentos-shared-${UUID.randomUUID()}")

            shouldThrow<AccessDeniedException> { authenticatedAs(alice) { namespaceApi.update(namespace.id, moved) } }
            namespaceService.findById(namespace.id)!!.sharedExchangePath shouldBe null

            authenticatedAs(root) { namespaceApi.update(namespace.id, moved) }
            namespaceService.findById(namespace.id)!!.sharedExchangePath shouldBe moved.sharedExchangePath
        }

        "the config writer writes with the authenticated user's rights" {
            shouldThrow<AccessDeniedException> { authenticatedAs(bob) { configWriter.setEnabled(agent.id, enabled = false) } }
            agentConfigService.findById(agent.id)!!.enabled shouldBe true

            authenticatedAs(alice) { configWriter.setEnabled(agent.id, enabled = false) }
            agentConfigService.findById(agent.id)!!.enabled shouldBe false
        }

        "with nobody authenticated, the config writer refuses with a clear message" {
            SecurityContextHolder.clearContext()

            val refused = shouldThrow<IllegalStateException> { configWriter.setEnabled(agent.id, enabled = false) }

            refused.message shouldBe "ConfigWriter must run with the user who applies the plan authenticated"
            agentConfigService.findById(agent.id)!!.enabled shouldBe true
        }
    }
}
