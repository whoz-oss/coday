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
 * Reconciles, at startup, the existence of the platform-scoped `LIST_USER_GROUP_MEMBERS`
 * configuration declared by [UserGroupToolConfigProperties.enabledByDefault].
 *
 * Mirrors [io.whozoss.agentos.queryUser.QueryUserConfigSeeder] exactly: when the property
 * is `true` (the shipped default) and no row exists yet, one is created with `autoGrant = true`
 * so every agent of the environment receives the `listUserGroupMembers` tool without any
 * admin action.
 *
 * ## Existence, not content
 *
 * Runs only when the configuration is **absent**. It never updates, never repairs, and never
 * reverts a drifted configuration: once the row exists it belongs to the administrator.
 *
 * ## Create-only, never delete
 *
 * Flipping [UserGroupToolConfigProperties.enabledByDefault] back to `false` on an
 * environment that was already seeded leaves the configuration in place. A warning is logged
 * naming the remedy instead of silently deleting data.
 *
 * ## Concurrency
 *
 * Multiple instances booting simultaneously may all see the row missing and race to create it.
 * The `tripleKey` unique constraint elects one winner; the others receive a 409 which is caught
 * and logged at info level — losing the race is the expected outcome, not a failure.
 *
 * Ordered after [io.whozoss.agentos.bootstrap.BootstrapServiceImpl] (order 100) so the admin
 * user exists before this seeder runs.
 */
@Service
@Order(101)
class UserGroupIntegrationConfigSeeder(
    private val properties: UserGroupToolConfigProperties,
    private val integrationConfigService: IntegrationConfigService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = seed()

    internal fun seed() {
        val existing =
            integrationConfigService.findByTriple(
                namespaceId = null,
                userId = null,
                name = DEFAULT_CONFIG_NAME,
            )
        val enabled = properties.enabledByDefault

        when {
            existing == null && enabled -> {
                createDefault()
            }

            existing == null -> {
                Unit
            }

            enabled -> {
                logger.info {
                    "[ListUserGroupMembersSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration already exists " +
                        "(autoGrant=${existing.autoGrant}), leaving it untouched."
                }
            }

            else -> {
                logger.warn {
                    "[ListUserGroupMembersSeeder] agentos.list-user-group-members.enabled-by-default is false but " +
                        "the platform '$DEFAULT_CONFIG_NAME' configuration still exists and is " +
                        "${if (existing.autoGrant) "still auto-granted to every agent" else "no longer auto-granted"}. " +
                        "This seeder never deletes persisted data. To stop the grant, set autoGrant=false on that " +
                        "configuration (or delete it) through the API."
                }
            }
        }
    }

    private fun createDefault() {
        try {
            integrationConfigService.create(defaultConfig())
            logger.info {
                "[ListUserGroupMembersSeeder] Created platform '$DEFAULT_CONFIG_NAME' configuration with autoGrant=true. " +
                    "Every agent of this environment receives the listUserGroupMembers tool; override it per namespace by " +
                    "creating a configuration with the same name."
            }
        } catch (e: ConflictException) {
            logger.info(e) {
                "[ListUserGroupMembersSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration created concurrently, skipping."
            }
        }
    }

    private fun defaultConfig() =
        IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = null,
            userId = null,
            name = DEFAULT_CONFIG_NAME,
            integrationType = UserGroupToolPlugin.INTEGRATION_TYPE,
            description = DEFAULT_DESCRIPTION,
            parameters = null,
            autoGrant = true,
        )

    companion object : KLogging() {
        /**
         * Name of the seeded platform configuration — a **contract**, not an implementation
         * detail. An administrator types it verbatim to create the namespace-scoped override
         * that shadows it, and it is the prefix of the tool name the LLM sees
         * (`LIST_USER_GROUP_MEMBERS__listUserGroupMembers`). Renaming it silently detaches
         * every existing override.
         */
        const val DEFAULT_CONFIG_NAME = "USER_GROUP"

        private const val DEFAULT_DESCRIPTION =
            "Lets an agent list all users belonging to a user group. " +
                "Created automatically from agentos.list-user-group-members.enabled-by-default."
    }
}
