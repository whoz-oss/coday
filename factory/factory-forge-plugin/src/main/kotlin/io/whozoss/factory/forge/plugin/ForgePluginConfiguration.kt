package io.whozoss.factory.forge.plugin

import io.whozoss.factory.forge.config.ForgeProperties
import io.whozoss.factory.forge.infrastructure.HttpJiraClient
import io.whozoss.factory.forge.port.JiraClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Spring configuration of the Forge plugin's child application context.
 *
 * Only the beans that are not component-scanned are declared here. All
 * `@Service`/`@Component` classes of the plugin are discovered by the context
 * scan performed by [ForgePlugin]; host beans (the `RestClient.Builder`, the
 * `AgentOsProxyClient`, the `TenantScopeProvider`, …) are resolved from the
 * parent context.
 */
@Configuration(proxyBeanMethods = false)
open class ForgePluginConfiguration {

    @Bean
    fun forgeProperties(): ForgeProperties = ForgeProperties.fromEnvironment()

    @Bean
    fun jiraClient(builder: RestClient.Builder, properties: ForgeProperties): JiraClient =
        HttpJiraClient(builder.clone(), properties.jira.baseUrl, properties.jira.email, properties.jira.apiToken)
}
