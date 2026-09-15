package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agent.AgentConfigProperties
import io.whozoss.agentos.sdk.api.agentConfig.AgentConfigDto
import jakarta.validation.Validation
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class DelegationTimeoutConfigUnitSpec : StringSpec({
    "binds the server default and rejects non-positive settings" {
        AgentConfigProperties().delegationTimeoutSeconds shouldBe 300
        val binder = Binder(MapConfigurationPropertySource(mapOf("agentos.defaults.delegation-timeout-seconds" to "1800")))
        val properties = binder.bind("agentos.defaults", Bindable.of(AgentConfigProperties::class.java)).get()
        properties.delegationTimeoutSeconds shouldBe 1800
        AgentConfigDefaultsController(properties).getAgentConfigDefaults().delegationTimeoutSeconds shouldBe 1800
        shouldThrow<IllegalArgumentException> { AgentConfigProperties(delegationTimeoutSeconds = 0) }
        shouldThrow<IllegalArgumentException> { AgentConfigProperties(delegationTimeoutSeconds = -1) }
    }

    "the shipped YAML resolves the deployment environment variable" {
        for ((configured, expected) in listOf(null to 300, "1800" to 1800)) {
            val environment = org.springframework.core.env.StandardEnvironment()
            environment.propertySources.remove("systemEnvironment")
            environment.propertySources.remove("systemProperties")
            environment.propertySources.addFirst(org.springframework.core.env.SystemEnvironmentPropertySource(
                "deployment", if (configured == null) emptyMap() else mapOf("AGENTOS_DEFAULTS_DELEGATION_TIMEOUT_SECONDS" to configured),
            ))
            org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", org.springframework.core.io.ClassPathResource("application.yml"))
                .forEach { environment.propertySources.addLast(it) }
            val binder = Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.get(environment),
                org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(environment),
            )
            binder.bind("agentos.defaults", Bindable.of(AgentConfigProperties::class.java)).get()
                .delegationTimeoutSeconds shouldBe expected
        }
    }

    "old agents inherit and explicit values survive domain node DTO and export mappings" {
        for (timeout in listOf(null, 1, 3600, Int.MAX_VALUE)) {
            val config = AgentConfig(namespaceId = null, name = "agent", delegationTimeoutSeconds = timeout)
            AgentConfigNode.fromDomain(config).toDomain().delegationTimeoutSeconds shouldBe timeout
            toDto(config).delegationTimeoutSeconds shouldBe timeout
            toDomain(toDto(config)).delegationTimeoutSeconds shouldBe timeout
        }
    }

    "validates API values while allowing null inheritance" {
        Validation.buildDefaultValidatorFactory().use { factory ->
            val validator = factory.validator
            for (timeout in listOf(null, 1, 1800)) {
                validator.validate(AgentConfigDto(name = "agent", delegationTimeoutSeconds = timeout)).isEmpty() shouldBe true
            }
            for (timeout in listOf(0, -1)) {
                validator.validate(AgentConfigDto(name = "agent", delegationTimeoutSeconds = timeout)).single().propertyPath.toString() shouldBe "delegationTimeoutSeconds"
                shouldThrow<IllegalArgumentException> {
                    AgentConfig(namespaceId = null, name = "agent", delegationTimeoutSeconds = timeout)
                }
            }
        }
        val dto = jacksonObjectMapper().readValue("""{"name":"agent"}""", AgentConfigDto::class.java)
        dto.delegationTimeoutSeconds shouldBe null
    }
})
