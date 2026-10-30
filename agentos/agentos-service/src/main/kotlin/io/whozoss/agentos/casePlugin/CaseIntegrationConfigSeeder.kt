package io.whozoss.agentos.casePlugin

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
 * Reconciles, at startup, the existence of the platform-scoped `CASE` configuration declared by
 * [CasePluginConfigProperties.enabledByDefault].
 *
 * Mirrors [io.whozoss.agentos.queryUser.QueryUserConfigSeeder], with one deliberate difference: the
 * seeded row is **not** auto-granted (`autoGrant = false`). `FreeCaseStarter` starts work, possibly
 * under another user's identity, so no agent receives it without naming the configuration in its
 * `integrations`. The seeded row only saves the administrator from creating it.
 * Setting `autoGrant = true` would be refused anyway: `CASE` is not in
 * [io.whozoss.agentos.integrationConfig.IntegrationsProperties.autoGrantableTypes].
 *
 * An agent then names `CASE` in its `integrations`, optionally narrowing it to one tool
 * (`CASE: [ReadCase]`). Tools are named `CASE__ReadCase`, `CASE__FreeCaseStarter` (for the current user) and
 * `CASE__FreeCaseStarterForUser` (for a user the caller names, ADMIN of the namespace only).
 *
 * ## Parameters
 *
 * Seeded without parameters, so the plugin defaults apply: all agents may be started
 * (`agents = ["*"]`), technical events included in transcripts. To restrict the agents, an
 * administrator creates an override of the same name at namespace scope; a user may create one for themselves.
 *
 * ## Existence, not content
 *
 * Runs only when the configuration is **absent**. It never updates, never repairs, and never reverts
 * a drifted configuration: once the row exists it belongs to the administrator.
 *
 * ## Create-only, never delete
 *
 * Flipping [CasePluginConfigProperties.enabledByDefault] back to `false` on an environment that was
 * already seeded leaves the configuration in place. A warning names the remedy instead of silently
 * deleting data.
 *
 * ## Concurrency
 *
 * Several instances booting at once all see the row missing and race to create it. The `tripleKey`
 * unique constraint elects one winner; the others receive a 409, caught and logged at info level:
 * losing the race is the expected outcome, not a failure.
 *
 * Ordered after [io.whozoss.agentos.queryUser.QueryUserConfigSeeder] (100) and
 * [io.whozoss.agentos.bootstrap.BootstrapServiceImpl], so the admin user exists first.
 */
@Service
@Order(102)
class CaseIntegrationConfigSeeder(
    private val properties: CasePluginConfigProperties,
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
            enabled -> logAlreadyExists(existing)
            else -> warnDisabledButPresent(existing)
        }
    }

    private fun createDefault() {
        try {
            integrationConfigService.unsecuredCreate(defaultConfig())
            logger.info {
                "[CaseSeeder] Created platform '$DEFAULT_CONFIG_NAME' configuration (autoGrant=false). " +
                    "An agent receives the ReadCase and FreeCaseStarter tools by naming it in its integrations; " +
                    "override it per namespace by creating a configuration with the same name."
            }
        } catch (e: ConflictException) {
            // Another instance won the startup race; the constraint did its job.
            logger.info(e) {
                "[CaseSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration created concurrently, skipping."
            }
        }
    }

    private fun defaultConfig() =
        IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = null,
            userId = null,
            name = DEFAULT_CONFIG_NAME,
            integrationType = CaseToolPlugin.INTEGRATION_TYPE,
            description = DEFAULT_DESCRIPTION,
            parameters = null,
            autoGrant = false,
        )

    private fun logAlreadyExists(existing: IntegrationConfig) {
        logger.info {
            "[CaseSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration already exists " +
                "(autoGrant=${existing.autoGrant}), leaving it untouched."
        }
    }

    private fun warnDisabledButPresent(existing: IntegrationConfig) {
        logger.warn {
            "[CaseSeeder] agentos.case-plugin.enabled-by-default is false but the platform " +
                "'$DEFAULT_CONFIG_NAME' configuration still exists (autoGrant=${existing.autoGrant}). " +
                "This seeder never deletes persisted data. To remove it, delete that configuration " +
                "through the API."
        }
    }

    companion object : KLogging() {
        /**
         * Name of the seeded platform configuration — a **contract**, not an implementation detail. An
         * administrator types it verbatim to create the namespace-scoped override that shadows it, an
         * agent names it in its `integrations`, and it is the prefix of the tool names the LLM sees
         * (`CASE__ReadCase`, `CASE__FreeCaseStarter`). Renaming it silently detaches every existing
         * override and agent binding.
         */
        const val DEFAULT_CONFIG_NAME = "CASE"

        private const val DEFAULT_DESCRIPTION =
            "Lets an agent read the transcript of another case (ReadCase) and start a new case with another " +
                "agent (FreeCaseStarter). Created automatically from agentos.case-plugin.enabled-by-default; " +
                "override it for a namespace with a configuration of the same name."
    }
}
