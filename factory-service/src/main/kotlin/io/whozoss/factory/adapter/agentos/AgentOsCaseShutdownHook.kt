package io.whozoss.factory.adapter.agentos

import mu.KotlinLogging
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * Graceful-shutdown trigger of the AgentOS execution boundary.
 *
 * When the Spring context closes, every case still tracked by the
 * [ActiveCaseRegistry] is interrupted/killed best-effort through the adapter
 * and its final state is logged. A case whose runtime is unreachable is
 * logged as not-confirmed-stopped — never silently treated as done.
 *
 * Kept separate from the registry so [ActiveCaseRegistry] stays unit-testable
 * without a Spring context.
 */
@Component
class AgentOsCaseShutdownHook(
    private val registry: ActiveCaseRegistry,
    private val adapter: AgentOsExecutionAdapter,
) : DisposableBean {

    private val logger = KotlinLogging.logger {}

    override fun destroy() {
        runCatching { registry.shutdownActiveCases(adapter) }
            .onFailure { logger.warn(it) { "Active-case shutdown did not complete cleanly" } }
    }
}
