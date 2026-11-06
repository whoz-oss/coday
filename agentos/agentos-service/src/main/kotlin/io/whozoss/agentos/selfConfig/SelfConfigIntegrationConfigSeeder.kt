package io.whozoss.agentos.selfConfig

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
 * Reconciles, at startup, the existence of the platform-scoped `SELF_CONFIG` integration
 * configuration declared by [SelfConfigProperties.enabledByDefault].
 *
 * When the property is `true` (the shipped default) and no row exists yet, one is created with
 * `autoGrant = false`. This makes the `SELF_CONFIG` type visible in the integration catalogue so
 * administrators can assign it to the agents meant to offer self-service configuration, without
 * having to know the type string. No agent receives the tools automatically -- and unlike
 * `QUERY_USER`, none ever can: `SELF_CONFIG` is not in
 * [io.whozoss.agentos.integrationConfig.IntegrationsProperties.autoGrantableTypes], so setting the
 * flag on it is refused outright. An agent able to rewrite a user's configuration is an explicit,
 * reviewed assignment.
 *
 * ## Existence, not content
 *
 * Runs only when the configuration is **absent**. It never updates, never repairs, and never
 * reverts a drifted configuration: once the row exists it belongs to the administrator.
 *
 * ## Create-only, never delete
 *
 * Flipping [SelfConfigProperties.enabledByDefault] back to `false` on an environment that was
 * already seeded leaves the configuration in place. A warning naming the remedy is logged instead:
 * deleting persisted data on the strength of a boolean would be a far worse surprise than the
 * asymmetry.
 *
 * ## Concurrency
 *
 * Several instances booting at once all see the row missing and race to create it. The `tripleKey`
 * unique constraint elects one winner; the others receive a 409, caught and logged at info level
 * -- losing that race is the expected outcome, not a failure, and letting it propagate would abort
 * the losing instance's startup.
 *
 * [IntegrationConfigService.unsecuredCreate] is used deliberately: startup seeding runs under no
 * user identity, which is precisely the case that surface exists for. Every other call site of
 * this feature goes through the `*ForCaller` surface -- see [SelfConfigToolService].
 *
 * Ordered at 103, after [io.whozoss.agentos.queryUser.QueryUserConfigSeeder] and
 * [io.whozoss.agentos.userGroup.UserGroupIntegrationConfigSeeder] (100) and
 * [io.whozoss.agentos.casePlugin.CaseIntegrationConfigSeeder] (102). Like them it only needs the
 * admin user created by [io.whozoss.agentos.bootstrap.BootstrapServiceImpl] to exist first; the
 * seeders are otherwise independent.
 */
@Service
@Order(103)
class SelfConfigIntegrationConfigSeeder(
    private val properties: SelfConfigProperties,
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
            existing == null && enabled -> createDefault()
            existing == null -> Unit // disabled and nothing persisted: nothing to reconcile
            enabled ->
                logger.info {
                    "[SelfConfigSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration already exists " +
                        "(autoGrant=${existing.autoGrant}), leaving it untouched."
                }
            else ->
                logger.warn {
                    "[SelfConfigSeeder] agentos.self-config.enabled-by-default is false but the platform " +
                        "'$DEFAULT_CONFIG_NAME' configuration still exists. This seeder never deletes " +
                        "persisted data. To remove it, delete that configuration through the API."
                }
        }
    }

    private fun createDefault() {
        try {
            integrationConfigService.unsecuredCreate(defaultConfig())
            logger.info {
                "[SelfConfigSeeder] Created platform '$DEFAULT_CONFIG_NAME' configuration (autoGrant=false). " +
                    "Assign it to the agents that should let users manage their own integration configurations."
            }
        } catch (e: ConflictException) {
            // Another instance won the startup race; the constraint did its job.
            logger.info(e) {
                "[SelfConfigSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration created concurrently, skipping."
            }
        }
    }

    private fun defaultConfig() =
        IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = null,
            userId = null,
            name = DEFAULT_CONFIG_NAME,
            integrationType = SelfConfigToolPlugin.INTEGRATION_TYPE,
            description = DEFAULT_DESCRIPTION,
            parameters = null,
            autoGrant = false,
        )

    companion object : KLogging() {
        /**
         * Name of the seeded platform configuration -- a **contract**, not an implementation
         * detail. An administrator types it verbatim to assign the integration to an agent, and it
         * is the prefix of the tool names the LLM sees (`SELF_CONFIG__listIntegrations`,
         * `SELF_CONFIG__createIntegration`, ...). Renaming it silently detaches every existing
         * assignment.
         */
        const val DEFAULT_CONFIG_NAME = "SELF_CONFIG"

        private const val DEFAULT_DESCRIPTION =
            "Lets an agent read the integration catalogue and manage the user's own personal " +
                "integration configurations. Assign to specific agents; never auto-granted. " +
                "Created automatically from agentos.self-config.enabled-by-default."
    }
}
