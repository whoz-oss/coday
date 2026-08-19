package io.whozoss.agentos.plugin

import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Exposes the [UserContextProvider] resolved from PF4J plugins as an optional Spring bean.
 *
 * The resolution happens once at startup rather than on every [io.whozoss.agentos.scheduledPrompt.ScheduledPromptExecutor]
 * invocation, improving performance and simplifying both the executor and its unit tests.
 *
 * The bean is null when no plugin registers a [UserContextProvider] implementation.
 */
@Configuration
@ConditionalOnProperty(name = ["agentos.prompt.scheduler.enabled"], havingValue = "true")
class PluginBeansConfiguration(
    private val userContextProviderResolver: UserContextProviderResolver,
) {
    @Bean
    fun userContextProvider(): UserContextProvider? = userContextProviderResolver.resolve()
}
