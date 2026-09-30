package io.whozoss.factory.adapter.agentos

import io.whozoss.factory.proxy.ProxyProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Wiring of the AgentOS execution adapter.
 *
 * The bean exists **only** when `factory.adapter.agentos.enabled=true`, so the
 * default build keeps `AgentOsAgentTurnCapability` → `HttpAgentOsProxyClient`
 * polling as the untouched, active turn driver. Nothing in
 * `SessionRunService` references this adapter.
 */
@Configuration
class AgentOsAdapterConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "factory.adapter.agentos", name = ["enabled"], havingValue = "true")
    fun agentOsExecutionAdapter(
        builder: RestClient.Builder,
        proxyProperties: ProxyProperties,
        properties: AgentOsAdapterProperties,
    ): AgentOsExecutionAdapter = DefaultAgentOsExecutionAdapter(
        builder = builder.clone(),
        baseUrl = proxyProperties.agentosUrl,
        sseClientFactory = { baseUrl ->
            AgentOsSseClient(
                baseUrl = baseUrl,
                backoffBaseMs = properties.backoffBaseMs,
                backoffMaxMs = properties.backoffMaxMs,
                maxReconnects = properties.maxReconnects,
                stallTimeoutMs = properties.stallTimeoutMs,
            )
        },
    )
}
