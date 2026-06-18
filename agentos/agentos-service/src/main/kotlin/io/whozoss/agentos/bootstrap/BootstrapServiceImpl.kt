package io.whozoss.agentos.bootstrap

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

/**
 * Guarantees the system always has at least one super-admin.
 *
 * Runs as an [ApplicationRunner] after the Spring context is ready and before the
 * application starts accepting requests, so all decisions are made on a single
 * thread without competing with HTTP-driven user creation.
 *
 * Two invariants are enforced:
 * 1. **Empty database** → create a default user with [effectiveAdminExternalId] and
 *    `isAdmin = true`.
 * 2. **Single user with `isAdmin = false`** → promote that user. Covers legacy
 *    deployments where the User predates the `isAdmin` field and was therefore
 *    never auto-promoted.
 *
 * The admin externalId is resolved with the following priority:
 * 1. `agentos.bootstrap.admin-external-id` property / `AGENTOS_BOOTSTRAP_ADMIN_EXTERNAL_ID`
 *    env var, when non-blank.
 * 2. JVM `user.name` system property (matches the OS user in `local` mode, so the
 *    bootstrap admin is immediately usable on first request without further config).
 * 3. `"admin"` sentinel as a last resort, only if `user.name` is somehow blank.
 *
 * In `auth` mode, the operator should set the property explicitly to the email
 * expected from JWT claims; otherwise the JVM `user.name` will not match any
 * incoming identity and a non-admin user will be auto-created on the first
 * authenticated request.
 *
 * Cases with two or more users where none is admin are intentionally not handled:
 * picking a winner would be arbitrary; an operator must intervene explicitly.
 */
@Service
@Order(2)
@ConditionalOnProperty(
    prefix = "agentos.bootstrap",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BootstrapServiceImpl(
    private val userService: UserService,
    @Value("\${agentos.bootstrap.admin-external-id:}")
    private val configuredAdminExternalId: String,
    @Value("\${user.name:admin}")
    private val jvmUserName: String,
) : BootstrapService, ApplicationRunner {

    private val effectiveAdminExternalId: String
        get() = configuredAdminExternalId.takeIf { it.isNotBlank() }
            ?: jvmUserName.takeIf { it.isNotBlank() }
            ?: "admin"

    override fun run(args: ApplicationArguments) {
        bootstrap()
    }

    override fun bootstrap() {
        logger.info { "[Bootstrap] Starting bootstrap process..." }

        when (val count = userService.count()) {
            0L -> createDefaultAdmin()
            1L -> promoteSingleUserIfNeeded()
            else -> logger.info { "[Bootstrap] $count users exist, no bootstrap action." }
        }

        logger.info { "[Bootstrap] Bootstrap process completed." }
    }

    override fun isBootstrapped(): Boolean {
        val userCount = userService.count()
        logger.debug { "[Bootstrap] Current user count: $userCount" }
        return userCount > 0
    }

    private fun createDefaultAdmin() {
        val externalId = effectiveAdminExternalId
        logger.info { "[Bootstrap] No users found. Creating default admin (externalId='$externalId')" }
        userService.create(
            User(
                metadata = EntityMetadata(),
                externalId = externalId,
                email = if (externalId.contains("@")) externalId else "",
                isAdmin = true,
            ),
        )
    }

    private fun promoteSingleUserIfNeeded() {
        val user = userService.findAll().firstOrNull() ?: run {
            logger.warn { "[Bootstrap] count()==1 but findAll() returned empty — race or repo inconsistency, skipping." }
            return
        }
        if (user.isAdmin) {
            logger.info { "[Bootstrap] Single user '${user.externalId}' is already admin, no migration needed." }
            return
        }
        logger.info { "[Bootstrap] Migrating single user '${user.externalId}' to super-admin" }
        userService.update(user.copy(isAdmin = true))
    }

    companion object : KLogging()
}
