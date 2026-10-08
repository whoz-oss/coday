package io.whozoss.agentos.queryUser

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Reconciles, at startup, the existence of the platform-scoped `QUERY_USER` configuration
 * declared by [QueryUserConfigProperties.enabledByDefault].
 *
 * ## Why a seeded row rather than a synthetic one
 *
 * An earlier design injected the default through a repository decorator, the way
 * [io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository] injects YAML
 * configs. It was rejected for one reason: such a configuration is *almost* an
 * [IntegrationConfig]. It appears in listings and merges correctly, but `PUT` and `DELETE`
 * on it answer 404 because `findById` has never heard of it. A row written once through the
 * ordinary service is indistinguishable from any other -- editable, deletable, exportable --
 * and that dullness is the whole point for a migration path.
 *
 * ## Existence, not content
 *
 * This runs only when the configuration is **absent**. It never updates, never repairs, and
 * never reverts a drifted configuration: once the row exists it belongs to the administrator.
 * That is also why no one-shot migration marker is needed. An administrator who wants to stop
 * the grant sets `autoGrant = false` rather than deleting the row, so the row still exists on
 * the next boot, so nothing is recreated.
 *
 * ## Create-only, never delete
 *
 * Flipping [QueryUserConfigProperties.enabledByDefault] back to `false` on an environment
 * that was already seeded leaves the configuration in place and still auto-granting. Deleting
 * persisted data on the strength of a boolean would be a far worse surprise than the
 * asymmetry, so the mismatch is reported as a warning naming the remedy instead.
 *
 * ## Concurrency
 *
 * Unlike [io.whozoss.agentos.bootstrap.BootstrapServiceImpl], which reasons on a single
 * instance, this may run on several instances booting at once. All of them will see the row
 * missing and try to create it; the `tripleKey` unique constraint elects one winner and
 * [io.whozoss.agentos.integrationConfig.IntegrationConfigServiceImpl] surfaces the others as
 * a 409. Losing that race is the expected outcome, not a failure, so the conflict is caught
 * and logged at info level -- letting it propagate would abort the losing instance's startup.
 *
 * Ordered after the default [ApplicationRunner] precedence so the admin user created by
 * [io.whozoss.agentos.bootstrap.BootstrapServiceImpl] exists first; the two are otherwise
 * independent.
 */
@Service
@Order(100)
class QueryUserConfigSeeder(
    private val properties: QueryUserConfigProperties,
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

        if (!properties.enabledByDefault) {
            if (existing != null) {
                logger.warn {
                    "[QueryUserSeeder] agentos.query-user.enabled-by-default is false but the platform " +
                        "'$DEFAULT_CONFIG_NAME' configuration still exists and is " +
                        "${if (existing.autoGrant) "still auto-granted to every agent" else "no longer auto-granted"}. " +
                        "This seeder never deletes persisted data. To stop the grant, set autoGrant=false on that " +
                        "configuration (or delete it) through the API."
                }
            }
            return
        }

        if (existing != null) {
            logger.info {
                "[QueryUserSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration already exists " +
                    "(autoGrant=${existing.autoGrant}), leaving it untouched."
            }
            return
        }

        try {
            integrationConfigService.create(
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = null,
                    name = DEFAULT_CONFIG_NAME,
                    integrationType = QueryUserToolPlugin.INTEGRATION_TYPE,
                    description = DEFAULT_DESCRIPTION,
                    parameters = DEFAULT_PARAMETERS,
                    autoGrant = true,
                ),
            )
            logger.info {
                "[QueryUserSeeder] Created platform '$DEFAULT_CONFIG_NAME' configuration with autoGrant=true. " +
                    "Every agent of this environment receives the queryUser tool; override it per namespace by " +
                    "creating a configuration with the same name."
            }
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.CONFLICT) {
                // Another instance won the startup race; the constraint did its job.
                logger.info { "[QueryUserSeeder] Platform '$DEFAULT_CONFIG_NAME' configuration created concurrently, skipping." }
            } else {
                throw e
            }
        }
    }

    companion object : KLogging() {
        /**
         * Name of the seeded platform configuration -- a **contract**, not an implementation
         * detail. An administrator types it verbatim to create the namespace-scoped override
         * that shadows it, and it is the prefix of the tool name the LLM sees
         * (`QUERY_USER__queryUser`). Renaming it silently detaches every existing override.
         */
        const val DEFAULT_CONFIG_NAME = "QUERY_USER"

        private const val DEFAULT_DESCRIPTION =
            "Lets an agent ask you a question mid-run and resume with your answer. " +
                "Created automatically from agentos.query-user.enabled-by-default; " +
                "override it for a namespace with a configuration of the same name."

        /**
         * All three forms allowed. Deliberately not configurable at environment level: an
         * administrator who wants to restrict them creates a namespace override, which is
         * exactly the mechanism this default exists to be overridden by.
         */
        private val DEFAULT_PARAMETERS =
            jacksonObjectMapper().readTree(
                """{"allowedQuestionTypes": ["FREE_TEXT", "SINGLE_CHOICE", "OPEN_CHOICE"]}""",
            )
    }
}
