package io.whozoss.agentos.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class UsageConfigPropertiesSpec : StringSpec({
    val contextRunner = ApplicationContextRunner().withUserConfiguration(UsagePropertiesTestConfiguration::class.java)

    "usage defaults to disabled when no property is supplied" {
        contextRunner.run { context ->
            context.getBean(UsageConfigProperties::class.java).enabled shouldBe false
        }
    }

    "usage can be explicitly enabled by configuration" {
        contextRunner.withPropertyValues("agentos.usage.enabled=true").run { context ->
            context.getBean(UsageConfigProperties::class.java).enabled shouldBe true
        }
    }

    listOf(false, true).forEach { enabled ->
        "usage binds AGENTOS_USAGE_ENABLED=$enabled from the environment" {
            contextRunner.withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        mapOf<String, Any>("AGENTOS_USAGE_ENABLED" to enabled.toString()),
                    ),
                )
            }.run { context ->
                context.getBean(UsageConfigProperties::class.java).enabled shouldBe enabled
            }
        }
    }

    "usage can be explicitly disabled by configuration" {
        contextRunner.withPropertyValues("agentos.usage.enabled=false").run { context ->
            context.getBean(UsageConfigProperties::class.java).enabled shouldBe false
        }
    }
})

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageConfigProperties::class)
private class UsagePropertiesTestConfiguration
