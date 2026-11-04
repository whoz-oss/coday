package io.whozoss.agentos.config

import io.kotest.assertions.throwables.shouldThrow
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
    // The `agentos.limits.*` placeholders read the system environment first, so a developer machine or a
    // runner that exports AGENTOS_LIMITS_* would otherwise decide what these specs see. Replacing that
    // property source keeps them about the shipped file; the override spec installs its own the same way.
    fun ApplicationContextRunner.withSystemEnvironment(variables: Map<String, Any> = emptyMap()) =
        withInitializer { context ->
            context.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    variables,
                ),
            )
        }

    val shipped =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(LimitsPropertiesTestConfiguration::class.java)
            .withSystemEnvironment()

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
            .withSystemEnvironment(mapOf("AGENTOS_LIMITS_AGENT_MAX_ITERATIONS" to "250"))
            .run { context ->
                context.getBean(LimitsConfigProperties::class.java).agentMaxIterations shouldBe 250
            }
    }

    // The code default stays null: a LimitsConfigProperties built by hand has no limit. The 2 is the shipped file's.
    "the code default of the platform run cost threshold is not defined" {
        LimitsConfigProperties().runCostThreshold shouldBe null
    }

    "the shipped application.yml sets the platform run cost threshold to 2" {
        shipped.run { context ->
            context.environment.getProperty("agentos.limits.run-cost-threshold") shouldBe "2"
            context.getBean(LimitsConfigProperties::class.java).runCostThreshold shouldBe 2.0
        }
    }

    "AGENTOS_LIMITS_RUN_COST_THRESHOLD overrides the shipped run cost threshold" {
        shipped
            .withSystemEnvironment(mapOf("AGENTOS_LIMITS_RUN_COST_THRESHOLD" to "7.5"))
            .run { context ->
                context.getBean(LimitsConfigProperties::class.java).runCostThreshold shouldBe 7.5
            }
    }

    "an empty AGENTOS_LIMITS_RUN_COST_THRESHOLD means no platform limit" {
        shipped
            .withSystemEnvironment(mapOf("AGENTOS_LIMITS_RUN_COST_THRESHOLD" to ""))
            .run { context ->
                context.getBean(LimitsConfigProperties::class.java).runCostThreshold shouldBe null
            }
    }

    // 0 is a threshold, not the absence of one: it asks for a confirmation on the first request.
    "AGENTOS_LIMITS_RUN_COST_THRESHOLD=0 stays a zero threshold" {
        shipped
            .withSystemEnvironment(mapOf("AGENTOS_LIMITS_RUN_COST_THRESHOLD" to "0"))
            .run { context ->
                context.getBean(LimitsConfigProperties::class.java).runCostThreshold shouldBe 0.0
            }
    }

    "the property can also be set directly" {
        shipped.withPropertyValues("agentos.limits.agent-max-iterations=7").run { context ->
            context.getBean(LimitsConfigProperties::class.java).agentMaxIterations shouldBe 7
        }
    }

    "the context compaction fractions default to 0.8 and 0.5, in the code and in the shipped application.yml" {
        LimitsConfigProperties().contextCompactionThreshold shouldBe 0.8
        LimitsConfigProperties().contextCompactionTarget shouldBe 0.5
        shipped.run { context ->
            with(context.getBean(LimitsConfigProperties::class.java)) {
                contextCompactionThreshold shouldBe 0.8
                contextCompactionTarget shouldBe 0.5
            }
            context.environment.getProperty("agentos.limits.context-compaction-threshold") shouldBe "0.8"
            context.environment.getProperty("agentos.limits.context-compaction-target") shouldBe "0.5"
        }
    }

    "AGENTOS_LIMITS_CONTEXT_COMPACTION_* override the shipped fractions" {
        shipped
            .withSystemEnvironment(
                mapOf(
                    "AGENTOS_LIMITS_CONTEXT_COMPACTION_THRESHOLD" to "0.9",
                    "AGENTOS_LIMITS_CONTEXT_COMPACTION_TARGET" to "0.4",
                ),
            ).run { context ->
                with(context.getBean(LimitsConfigProperties::class.java)) {
                    contextCompactionThreshold shouldBe 0.9
                    contextCompactionTarget shouldBe 0.4
                }
            }
    }

    "fractions that do not satisfy 0 < target < threshold <= 1 are refused at construction" {
        listOf(
            0.0 to 0.8,
            0.8 to 0.8,
            0.9 to 0.8,
            0.5 to 1.1,
            -0.5 to 0.8,
        ).forEach { (target, threshold) ->
            shouldThrow<IllegalArgumentException> {
                LimitsConfigProperties(contextCompactionThreshold = threshold, contextCompactionTarget = target)
            }
        }
        LimitsConfigProperties(contextCompactionThreshold = 1.0, contextCompactionTarget = 0.1).contextCompactionThreshold shouldBe 1.0
    }

    "the service refuses to start with inverted fractions, as with any invalid limit" {
        shipped
            .withPropertyValues(
                "agentos.limits.context-compaction-threshold=0.4",
                "agentos.limits.context-compaction-target=0.6",
            ).run { context ->
                (context.startupFailure != null) shouldBe true
            }
    }
})

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LimitsConfigProperties::class)
private class LimitsPropertiesTestConfiguration
