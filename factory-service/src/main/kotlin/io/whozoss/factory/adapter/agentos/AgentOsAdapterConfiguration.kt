package io.whozoss.factory.adapter.agentos

import io.whozoss.factory.proxy.ProxyProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Wiring of the AgentOS execution adapter.
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
