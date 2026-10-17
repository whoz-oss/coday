package io.whozoss.agentos.plugin

import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Exposes the [UserContextProvider] resolved from PF4J plugins as an optional Spring bean.
 *
 * The resolution happens once at startup rather than on every use, improving performance and simplifying
 * its consumers and their unit tests. The only consumer is [io.whozoss.agentos.context.UserSessionContextResolver],
 * shared by the scheduled prompts and by `FreeCaseLauncher` (through `CaseLaunchService`), so this configuration
 * is not tied to the scheduler: `FreeCaseLauncher` needs the provider whether or not the scheduler is enabled.
 *
 * The bean is null when no plugin registers a [UserContextProvider] implementation.
 */
@Configuration
class PluginBeansConfiguration(
    private val userContextProviderResolver: UserContextProviderResolver,
) {
    @Bean
    fun userContextProvider(): UserContextProvider? = userContextProviderResolver.resolve()
}
