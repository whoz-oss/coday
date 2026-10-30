package io.whozoss.agentos.userGroup

import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reconciles, at startup, the existence of the platform-scoped `USER_GROUP` integration
 * configuration declared by [UserGroupToolConfigProperties.enabledByDefault].
 *
 * When [UserGroupToolConfigProperties.enabledByDefault] is `true` (the shipped default)
 * and no row exists yet, one is created with `autoGrant = false`. This makes the
 * `USER_GROUP` type visible in the integration catalogue so administrators can assign it
 * to specific agents without having to know the type string. No agent receives the tools
 * automatically — an explicit assignment is required.
 *
 * ## Existence, not content
 *
 * Runs only when the configuration is **absent**. It never updates, never repairs, and
 * never reverts a drifted configuration: once the row exists it belongs to the administrator.
 *
 * ## Create-only, never delete
 *
 * Flipping [UserGroupToolConfigProperties.enabledByDefault] back to `false` on an
 * environment that was already seeded leaves the configuration in place. A warning is
 * logged naming the remedy instead of silently deleting data.
 *
 * ## Concurrency
 *
 * Multiple instances booting simultaneously may all see the row missing and race to
 * create it. The `tripleKey` unique constraint elects one winner; the others receive a
 * 409 which is caught and logged at info level — losing the race is the expected outcome.
 *
 * Ordered at 100 — the same as [io.whozoss.agentos.queryUser.QueryUserConfigSeeder] —
 * because both only need the admin user to exist (created by
 * [io.whozoss.agentos.bootstrap.BootstrapServiceImpl] at the default order) and are
 * otherwise independent.
 */
@Service
@Order(100)
class UserGroupIntegrationConfigSeeder(
    private val properties: UserGroupToolConfigProperties,
    private val integrationConfigService: IntegrationConfigService,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) = seed()

    internal fun seed() {
        val existing = integrationConfigService.findByTriple(
            namespaceId = null,
            userId = null,
            name = DEFAULT_CONFIG_NAME,
        )
        val enabled = properties.enabledByDefault

        when {
            existing == null && enabled -> createDefault()
            existing == null -> Unit
            enabled -> logger.info {
                "[UserGroupSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration already exists " +
                    "(autoGrant=${existing.autoGrant}), leaving it untouched."
            }
            else -> logger.warn {
                "[UserGroupSeeder] agentos.user-group.enabled-by-default is false but " +
                    "the platform '$DEFAULT_CONFIG_NAME' configuration still exists. " +
                    "This seeder never deletes persisted data. To remove it, delete it through the API."
            }
        }
    }

    private fun createDefault() {
        try {
            integrationConfigService.unsecuredCreate(defaultConfig())
            logger.info {
                "[UserGroupSeeder] Created platform '$DEFAULT_CONFIG_NAME' configuration (autoGrant=false). " +
                    "Assign it to specific agents to grant them the USER_GROUP tools."
            }
        } catch (e: ConflictException) {
            logger.info(e) {
                "[UserGroupSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration created concurrently, skipping."
            }
        }
    }

    private fun defaultConfig() = IntegrationConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = null,
        userId = null,
        name = DEFAULT_CONFIG_NAME,
        integrationType = UserGroupToolPlugin.INTEGRATION_TYPE,
        description = DEFAULT_DESCRIPTION,
        parameters = null,
        autoGrant = false,
    )

    companion object : KLogging() {
        /**
         * Name of the seeded platform configuration — a **contract**, not an implementation
         * detail. Admins type it verbatim to assign the integration to an agent, and it is
         * the prefix of the tool names the LLM sees (`USER_GROUP__listMembers`,
         * `USER_GROUP__listAgentUsers`, `USER_GROUP__listGroupAgentUsers`).
         * Renaming it silently detaches every existing assignment.
         */
        const val DEFAULT_CONFIG_NAME = "USER_GROUP"

        private const val DEFAULT_DESCRIPTION =
            "Lets an agent query UserGroup membership and agent deployment. " +
                "Assign to specific agents; not auto-granted. " +
                "Created automatically from agentos.user-group.enabled-by-default."
    }
}
