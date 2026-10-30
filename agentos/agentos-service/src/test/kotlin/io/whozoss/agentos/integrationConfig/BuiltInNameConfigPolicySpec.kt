package io.whozoss.agentos.integrationConfig

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor

class BuiltInNameConfigPolicySpec :
    StringSpec({
        val registry =
            CompositeIntegrationTypeRegistry().apply {
                registerBuiltIn(IntegrationTypeDescriptor(type = "AGENTOS_CONFIG", displayName = "AgentOS configuration", description = "", configSchema = null, builtIn = true))
            }
        val policy = BuiltInNameConfigPolicy(registry)

        fun config(name: String) = IntegrationConfig(name = name, integrationType = "HTTP_API")

        "a configuration cannot take the name of a built-in integration, whatever its case" {
            shouldThrow<UnprocessableEntityException> { policy.validate(config("AGENTOS_CONFIG")) }
            shouldThrow<UnprocessableEntityException> { policy.validate(config(" agentos_config ")) }
        }

        "any other name is left to the type's own rules" {
            shouldNotThrowAny { policy.validate(config("AGENTOS_CONFIG_MIRROR")) }
        }
    })
