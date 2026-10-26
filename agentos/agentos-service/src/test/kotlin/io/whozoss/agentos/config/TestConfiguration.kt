package io.whozoss.agentos.config

import io.whozoss.agentos.workflow.LoopConfigResolver
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Provides beans that are needed in the test application context but are not
 * auto-configured in the test profile.
 *
 * The [ObjectMapper] beans ([ObjectMapperConfiguration.jacksonObjectMapper],
 * [ObjectMapperConfiguration.yamlMapper], [ObjectMapperConfiguration.yamlExportMapper])
 * are declared in the production [ObjectMapperConfiguration] and are always available
 * in the Spring context — no override needed here.
 */
@TestConfiguration
class TestConfiguration {
    /**
     * No-op [LoopConfigResolver] for integration tests that run with the scheduler disabled
     * (`agentos.prompt.scheduler.enabled=false` in `application-test.yml`).
     *
     * [io.whozoss.agentos.scheduledPrompt.ScheduledPromptLoopConfigResolver] is
     * `@ConditionalOnProperty(scheduler.enabled=true)` and is therefore absent in the test
     * context. Without this bean, `AgentServiceImpl` fails to start.
     *
     * A loop case triggered in a test with this resolver will abort with a clear error message
     * rather than a `NoSuchBeanDefinitionException` at context startup, which is the correct
     * behaviour. Tests that exercise loop execution should override this with `@MockkBean`.
     */
    @Bean
    fun noOpLoopConfigResolver(): LoopConfigResolver = LoopConfigResolver { null }
}
