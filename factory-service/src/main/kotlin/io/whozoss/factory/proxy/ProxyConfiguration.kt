package io.whozoss.factory.proxy

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Wiring of the generic AgentOS relay adapter.
 *
 * The proxy URL is taken from [ProxyProperties] so no module reads `process.env`
 * directly. This is core infrastructure (the `/api/agents` relay), independent
 * of the optional Forge plugin.
 */
@Configuration
class ProxyConfiguration {

    @Bean
    fun agentOsProxyClient(builder: RestClient.Builder, properties: ProxyProperties): AgentOsProxyClient =
        HttpAgentOsProxyClient(builder.clone(), properties.agentosUrl)
}
