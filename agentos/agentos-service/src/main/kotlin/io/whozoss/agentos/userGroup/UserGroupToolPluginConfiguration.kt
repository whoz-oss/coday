package io.whozoss.agentos.userGroup

import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring configuration for the USER_GROUP internal integration.
 *
 * Instantiates [UserGroupToolPlugin] as a `@Bean` so that
 * [io.whozoss.agentos.tool.ToolRegistryService] can discover it via
 * `springToolPlugins: List<ToolPlugin>` without a PF4J JAR.
 *
 * All functional logic (auth checks, namespace isolation) lives in [UserGroupToolService],
 * which is injected here and wired into the plugin as method references. This class
 * contains no business logic — it only binds dependencies.
 */
@Configuration
class UserGroupToolPluginConfiguration(
    private val userGroupToolService: UserGroupToolService,
) {
    @Bean
    fun userGroupToolPlugin(): ToolPlugin =
        UserGroupToolPlugin(
            membersLoader = userGroupToolService::getMembers,
            usersByAgentLoader = userGroupToolService::getUsersByAgent,
            membersByGroupAndAgentLoader = userGroupToolService::getMembersByGroupAndAgent,
        )
}
