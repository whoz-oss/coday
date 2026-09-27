package io.whozoss.factory.runs.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.runs.*` configuration tree.
 *
 * Mirrors the environment variables the Node dashboard reads for the legacy
 * JSONL runs surface:
 *   - `FACTORY_RUNS_DIR` / `FACTORY_RUN_ENTRY`
 *   - `AGENTOS_URL` (AgentOS base URL, also consumed by the generic proxy)
 *   - `JIRA_BASE_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN`
 *
 * The legacy runs aggregate is core infrastructure; it must not depend on the
 * optional Forge plugin's configuration.
 */
@ConfigurationProperties(prefix = "factory.runs")
data class LegacyRunProperties(
    val dir: String = "factory/runs",
    val entry: String = "factory/run.mjs",
    val agentosUrl: String = "http://localhost:8080",
    val jiraBaseUrl: String? = null,
    val jiraEmail: String? = null,
    val jiraApiToken: String? = null,
)
