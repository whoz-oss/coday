package io.whozoss.agentos.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The platform default of the namespace "maximum tool rounds" setting is
 * `agentos.limits.agent-max-iterations`. These specs load the shipped `application.yml`, so they
 * guard the value operators actually get, and the environment variable that overrides it.
 */
class LimitsConfigPropertiesSpec : StringSpec({
    val shipped =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LimitsPropertiesTestConfiguration::class.java)

    "the code default of the platform tool rounds is 1000" {
        LimitsConfigProperties().agentMaxIterations shouldBe 1000
    }

    "the shipped application.yml sets the platform tool rounds to 1000" {
        shipped.run { context ->
            context.getBean(LimitsConfigProperties::class.java).agentMaxIterations shouldBe 1000
        }
    }

    // The code default is also 1000, so the bean alone cannot tell a well-placed YAML key from one that
    // never reached the `agentos.limits` prefix (which is how this file used to be indented). Reading the
    // property from the environment does.
    "the shipped application.yml defines the tool rounds under agentos.limits" {
        shipped.run { context ->
            context.environment.getProperty("agentos.limits.agent-max-iterations") shouldBe "1000"
            context.environment.getProperty("agentos.limits.case-max-iterations") shouldBe "100"
            context.environment.getProperty("agentos.limits.agent-loop-max-items") shouldBe "50"
        }
    }

    "AGENTOS_LIMITS_AGENT_MAX_ITERATIONS overrides the shipped platform tool rounds" {
        shipped
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        mapOf<String, Any>("AGENTOS_LIMITS_AGENT_MAX_ITERATIONS" to "250"),
                    ),
                )
            }.run { context ->
                context.getBean(LimitsConfigProperties::class.java).agentMaxIterations shouldBe 250
            }
    }

    "the property can also be set directly" {
        shipped.withPropertyValues("agentos.limits.agent-max-iterations=7").run { context ->
            context.getBean(LimitsConfigProperties::class.java).agentMaxIterations shouldBe 7
        }
    }
})

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LimitsConfigProperties::class)
private class LimitsPropertiesTestConfiguration
