package io.whozoss.factory.forge.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.forge.*` configuration tree.
 *
 * Mirrors the environment variables the Node dashboard reads for the Forge,
 * runs, Jira and AgentOS surfaces:
 *   - `AGENTOS_URL` (AgentOS proxy base URL)
 *   - `JIRA_BASE_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN`
 *   - the legacy runs directory and run entry point
 */
@ConfigurationProperties(prefix = "factory.forge")
data class ForgeProperties(
    val agentosUrl: String = "http://localhost:8080",
    val runsDir: String = "factory/runs",
    val runEntry: String = "factory/run.mjs",
    val jira: Jira = Jira(),
) {
    data class Jira(
        val baseUrl: String? = null,
        val email: String? = null,
        val apiToken: String? = null,
    ) {
        /** True when all three credentials are present and non-blank. */
        val configured: Boolean
            get() = !baseUrl.isNullOrBlank() && !email.isNullOrBlank() && !apiToken.isNullOrBlank()

        /** The missing credential variable names, in canonical order. */
        fun missing(): List<String> = buildList {
            if (baseUrl.isNullOrBlank()) add("JIRA_BASE_URL")
            if (email.isNullOrBlank()) add("JIRA_EMAIL")
            if (apiToken.isNullOrBlank()) add("JIRA_API_TOKEN")
        }
    }
}
