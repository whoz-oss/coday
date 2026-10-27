package io.whozoss.agentos.selfConfig

import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the `SELF_CONFIG` internal integration.
 *
 * Instantiates [SelfConfigToolPlugin] as a `@Bean` so that
 * [io.whozoss.agentos.tool.ToolRegistryService] discovers it through its
 * `springToolPlugins: List<ToolPlugin>` constructor parameter, without a PF4J jar.
 *
 * Declared in a dedicated `@Configuration` class rather than annotating the plugin
 * `@Component`, for the reason documented on
 * [io.whozoss.agentos.userGroup.UserGroupToolPluginConfiguration] and
 * [io.whozoss.agentos.redirect.RedirectConfiguration]: `ToolRegistryService` collects every
 * `ToolPlugin` bean, so a plugin that injects a service sitting above it in the dependency graph
 * closes a cycle. Here the only dependency is [SelfConfigToolService], which injects
 * `IntegrationConfigService`, `UserService` and `IntegrationTypeRegistry` -- none of which depends
 * on `ToolRegistryService` -- so no cycle forms.
 *
 * This class holds no business logic: all authorization and all rules live in
 * [SelfConfigToolService].
 */
@Configuration
class SelfConfigToolPluginConfiguration(
    private val selfConfigToolService: SelfConfigToolService,
) {
    @Bean
    fun selfConfigToolPlugin(): ToolPlugin = SelfConfigToolPlugin(selfConfigToolService)
}
