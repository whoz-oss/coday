package io.whozoss.factory.forge.plugin

import io.whozoss.factory.forge.config.ForgeProperties
import io.whozoss.factory.forge.infrastructure.HttpJiraClient
import io.whozoss.factory.forge.port.JiraClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Spring configuration of the Forge plugin's child application context.
 */
@Configuration(proxyBeanMethods = false)
open class ForgePluginConfiguration {

    @Bean
    fun forgeProperties(): ForgeProperties = ForgeProperties.fromEnvironment()

    @Bean
    fun jiraClient(
        builderProvider: ObjectProvider<RestClient.Builder>,
        properties: ForgeProperties,
    ): JiraClient {
        val builder = builderProvider.ifAvailable?.clone() ?: RestClient.builder()
        return HttpJiraClient(builder, properties.jira.baseUrl, properties.jira.email, properties.jira.apiToken)
    }
}
