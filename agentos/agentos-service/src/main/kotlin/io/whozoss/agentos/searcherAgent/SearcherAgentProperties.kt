package io.whozoss.agentos.searcherAgent

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `agentos.defaults.searcher-agent` (env `AGENTOS_DEFAULTS_SEARCHER_AGENT_ENABLED`).
 *
 * [enabled] declares that each personal namespace should own a namespace-scoped, read-only agent named
 * `Searcher`, created and deployed when the namespace is provisioned; see [SearcherAgentSeeder].
 */
@ConfigurationProperties(prefix = "agentos.defaults.searcher-agent")
data class SearcherAgentProperties(
    val enabled: Boolean = true,
)
