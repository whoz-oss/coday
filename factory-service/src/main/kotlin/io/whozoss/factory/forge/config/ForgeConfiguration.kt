package io.whozoss.factory.forge.config

import io.whozoss.factory.forge.infrastructure.HttpAgentOsProxyClient
import io.whozoss.factory.forge.infrastructure.HttpJiraClient
import io.whozoss.factory.forge.port.AgentOsProxyClient
import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.runs.service.LegacyRunService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Wiring of the Forge/AgentOS/Jira/legacy-runs adapters.
 *
 * The HTTP clients take their configuration from [ForgeProperties] so no module
 * reads `process.env` directly.
 */
@Configuration
class ForgeConfiguration {

    @Bean
    fun agentOsProxyClient(builder: RestClient.Builder, properties: ForgeProperties): AgentOsProxyClient =
        HttpAgentOsProxyClient(builder.clone(), properties.agentosUrl)

    @Bean
    fun jiraClient(builder: RestClient.Builder, properties: ForgeProperties): JiraClient =
        HttpJiraClient(builder.clone(), properties.jira.baseUrl, properties.jira.email, properties.jira.apiToken)

    @Bean
    fun legacyRunService(properties: ForgeProperties): LegacyRunService =
        LegacyRunService(
            runsDir = properties.runsDir,
            runEntry = properties.runEntry,
            agentosUrl = properties.agentosUrl,
            jiraBaseUrl = properties.jira.baseUrl,
            jiraEmail = properties.jira.email,
            jiraApiToken = properties.jira.apiToken,
        )
}
