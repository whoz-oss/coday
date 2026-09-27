package io.whozoss.factory.forge.config

/**
 * Configuration of the Forge plugin, read from the process environment.
 *
 * The plugin is loaded by PF4J into the host JVM; it is *not* a Spring Boot
 * application and therefore does not rely on the core `application.yml`. This
 * mirrors the environment variables the Node dashboard reads for the Forge,
 * runs, Jira and AgentOS surfaces:
 *   - `AGENTOS_URL` (AgentOS proxy base URL)
 *   - `JIRA_BASE_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN`
 *   - the legacy runs directory and run entry point
 *
 * System properties (e.g. `-Dfactory.forge.agentos-url=...`) take precedence
 * over environment variables, then the built-in defaults apply.
 */
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

    companion object {
        /** Build the properties from sysprops (preferred) then environment variables. */
        fun fromEnvironment(env: (String) -> String? = { System.getenv(it) }): ForgeProperties =
            ForgeProperties(
                agentosUrl = read("factory.forge.agentos-url", "AGENTOS_URL", env) ?: "http://localhost:8080",
                runsDir = read("factory.forge.runs-dir", "FACTORY_RUNS_DIR", env) ?: "factory/runs",
                runEntry = read("factory.forge.run-entry", "FACTORY_RUN_ENTRY", env) ?: "factory/run.mjs",
                jira = Jira(
                    baseUrl = read("factory.forge.jira.base-url", "JIRA_BASE_URL", env),
                    email = read("factory.forge.jira.email", "JIRA_EMAIL", env),
                    apiToken = read("factory.forge.jira.api-token", "JIRA_API_TOKEN", env),
                ),
            )

        private fun read(property: String, variable: String, env: (String) -> String?): String? =
            System.getProperty(property)?.takeIf { it.isNotBlank() }
                ?: env(variable)?.takeIf { it.isNotBlank() }
    }
}
