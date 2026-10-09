package io.whozoss.factory.proxy

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.proxy.*` configuration tree.
 *
 * Mirrors the `AGENTOS_URL` environment variable the Node dashboard read to
 * reach AgentOS.
 */
@ConfigurationProperties(prefix = "factory.proxy")
data class ProxyProperties(
    val agentosUrl: String = "http://localhost:8080",
)
