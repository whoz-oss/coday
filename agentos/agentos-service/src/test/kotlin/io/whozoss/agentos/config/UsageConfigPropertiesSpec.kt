package io.whozoss.agentos.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

class UsageConfigPropertiesSpec : StringSpec({
    val contextRunner = ApplicationContextRunner().withUserConfiguration(UsagePropertiesTestConfiguration::class.java)

    "the code default is disabled, so a class built without configuration is inert" {
        UsageConfigProperties().enabled shouldBe false
        contextRunner.run { context ->
            context.getBean(UsageConfigProperties::class.java).enabled shouldBe false
        }
    }

    "the shipped application.yml enables usage tracking by default" {
        // Loads the real application.yml: the switch is on unless AGENTOS_USAGE_ENABLED says otherwise.
        contextRunner.withInitializer(ConfigDataApplicationContextInitializer()).run { context ->
            context.getBean(UsageConfigProperties::class.java).enabled shouldBe true
        }
    }

    "the shipped application.yml can be switched off with AGENTOS_USAGE_ENABLED=false" {
        contextRunner
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withInitializer { context ->
                context.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        mapOf<String, Any>("AGENTOS_USAGE_ENABLED" to "false"),
                    ),
                )
            }.run { context ->
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
