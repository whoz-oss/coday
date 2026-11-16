package io.whozoss.agentos.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired

/**
 * Base class for the specs that call the application as the local OS identity and need that caller to
 * be a super-admin.
 *
 * [io.whozoss.agentos.user.UserServiceImpl.resolveOrCreateByExternalId] grants super-admin to the
 * first user of an empty database. That held by accident while every Spring test context owned a
 * database, and stopped holding once they share one
 * ([io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jHarness]): a spec that ran earlier may have
 * created users, or wiped the database and with it this identity. Promoting the caller here states the
 * requirement instead of inheriting it from the order specs happen to run in — set
 * `specExecutionOrder = SpecExecutionOrder.Random` in `ProjectConfig` and the specs that relied on the
 * accident fail.
 *
 * The promotion runs before every test rather than once per spec: `ProjectConfig` sets
 * [io.kotest.core.spec.IsolationMode.InstancePerLeaf], so Kotest builds one instance of the spec per
 * leaf test and calls `beforeSpec` on each of them. It is an upsert on the user's id, so repeating it
 * costs one write and changes nothing — and a subclass must not undo it, in particular not with a
 * [io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport.clearDatabase] in a `beforeEach`.
 *
 * Carries no annotation, so a subclass keeps its own `@SpringBootTest` configuration and its own
 * Spring context.
 *
 * Not for a spec that exercises permission denial: one that asserts a 403 mocks [UserService] and
 * hands itself the identity it wants, the way
 * `io.whozoss.agentos.scheduledPrompt.ScheduledPromptControllerPermissionIntegrationSpec` does.
 */
abstract class SuperAdminCallerSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var userService: UserService

    @Autowired private lateinit var users: UserRepository

    init {
        beforeSpec { users.save(userService.getCurrentUser().copy(isAdmin = true)) }
    }
}
