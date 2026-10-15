package io.whozoss.factory.adapter.agentos

import io.whozoss.factory.proxy.ProxyProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Spring wiring of the AgentOS execution boundary (Phase 3).
 *
 * Phase 3 introduced the durable, runtime-agnostic agent-execution adapter and
 * its supporting lifecycle machinery. This configuration assembles those
 * collaborators into the single [agentOsExecutionAdapter] bean driving
 * `CapabilityExecutionService`:
 *
 * - [DefaultAgentOsExecutionAdapter] — durable SSE-bridge implementation of the
 *   [AgentRuntimeAdapter] contract (create / turn start / observation /
 *   reconciliation / shutdown of a case).
 * - [ActiveCaseRegistry] — process-wide, thread-safe registry of every active
 *   case driven by this Factory instance, used for status tracking and graceful
 *   shutdown ([AgentOsCaseShutdownHook]).
 * - [TrustedCaseBinding] — Factory-authority identities bound to each case,
 *   never sourced from LLM arguments.
 * - [HighWaterMark] — durable per-turn baseline fencing out older case events.
 * - [VerdictDeriver] — derives the [AgentOsExecutionVerdict] from observed
 *   case events.
 *
 * The bean is **always created** since the final cutover: the durable SSE bridge
 * is the primary, mandatory execution driver of `CapabilityExecutionService`.
 * `factory.adapter.agentos.enabled` (default `true`) no longer gates the bean —
 * it selects the driver: when set to `false` the service explicitly falls back to
 * the legacy `HttpAgentOsProxyClient` polling path while still depending on this
 * adapter instance (which is then never invoked).
 */
@Configuration
class AgentOsAdapterConfiguration {

    @Bean
    fun agentOsExecutionAdapter(
        builder: RestClient.Builder,
        proxyProperties: ProxyProperties,
        properties: AgentOsAdapterProperties,
        activeCaseRegistry: ActiveCaseRegistry,
    ): AgentOsExecutionAdapter = DefaultAgentOsExecutionAdapter(
        builder = builder.clone(),
        baseUrl = proxyProperties.agentosUrl,
        bindingSecret = properties.bindingSecret,
        registry = activeCaseRegistry,
        sseClientFactory = { baseUrl ->
            AgentOsSseClient(
                baseUrl = baseUrl,
                backoffBaseMs = properties.backoffBaseMs,
                backoffMaxMs = properties.backoffMaxMs,
                maxReconnects = properties.maxReconnects,
                stallTimeoutMs = properties.stallTimeoutMs,
                humanWaitTimeoutMs = properties.humanWaitTimeoutMs,
            )
        },
    )
}
