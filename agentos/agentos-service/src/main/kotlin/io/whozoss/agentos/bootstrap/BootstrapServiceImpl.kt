package io.whozoss.agentos.bootstrap

import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Guarantees the system always has at least one super-admin.
 *
 * Runs as an [ApplicationRunner] after the Spring context is ready and before the
 * application starts accepting requests, so all decisions are made on a single
 * thread without competing with HTTP-driven user creation.
 *
 * Two invariants are enforced:
 * 1. **Empty database** → create a default user with [defaultAdminExternalId] and
 *    `isAdmin = true`. The external id is configurable so an operator can match
 *    their own OS username (mode `local`) and have an immediately usable admin
 *    on first request.
 * 2. **Single user with `isAdmin = false`** → promote that user. Covers legacy
 *    deployments where the User predates the `isAdmin` field and was therefore
 *    never auto-promoted.
 *
 * Cases with two or more users where none is admin are intentionally not handled:
 * picking a winner would be arbitrary; an operator must intervene explicitly.
 */
@Service
@ConditionalOnProperty(
    prefix = "agentos.bootstrap",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class BootstrapServiceImpl(
    private val userService: UserService,
    @Value("\${agentos.bootstrap.admin-external-id:admin}")
    private val defaultAdminExternalId: String,
) : BootstrapService, ApplicationRunner {

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
        logger.info { "[Bootstrap] No users found. Creating default admin (externalId='$defaultAdminExternalId')" }
        userService.create(
            User(
                metadata = EntityMetadata(),
                externalId = defaultAdminExternalId,
                email = if (defaultAdminExternalId.contains("@")) defaultAdminExternalId else "",
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
