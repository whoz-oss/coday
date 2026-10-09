package io.whozoss.factory.workflow.service

import io.whozoss.factory.persistence.TenantScopeProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Bootstraps the bundled declarative session definitions into the configured
 * default tenant at startup (`factory.workflow.seed.enabled`, enabled by
 * default; disabled by the `openapi` profile).
 *
 * The seed is best-effort: a failure (e.g. an unavailable datasource during
 * spec generation) is logged and MUST NOT prevent the service from starting.
 * It is idempotent — an already-registered `workflowType@version` is never
 * overwritten.
 */
@Component
@ConditionalOnProperty(name = ["factory.workflow.seed.enabled"], havingValue = "true", matchIfMissing = true)
class SessionDefinitionSeedRunner(
    private val seeder: WorkflowDefinitionSeeder,
    private val tenantScopeProvider: TenantScopeProvider,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(SessionDefinitionSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        runCatching { seeder.seed(tenantScopeProvider.defaultScope()) }
            .onSuccess { seeded ->
                if (seeded.isNotEmpty()) log.info("Seeded {} declarative session definition(s): {}", seeded.size, seeded)
            }
            .onFailure { log.warn("Declarative session definition seed skipped: {}", it.message) }
    }
}
