package io.whozoss.agentos.integrationConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor

/**
 * Built-in integration types (e.g. the file exchange) are surfaced for the UI catalogue via
 * [CompositeIntegrationTypeRegistry.registerBuiltIn], independent of the [ToolPlugin] path.
 */
class CompositeIntegrationTypeRegistrySpec :
    StringSpec({
        "registerBuiltIn exposes the descriptor via listTypes and findByType" {
            val registry = CompositeIntegrationTypeRegistry()
            val descriptor =
                IntegrationTypeDescriptor(
                    type = "CASE_FILE_EXCHANGE",
                    displayName = "Case file exchange",
                    description = "x",
                    configSchema = null,
                    builtIn = true,
                )

            registry.registerBuiltIn(descriptor)

            registry.findByType("CASE_FILE_EXCHANGE") shouldBe descriptor
            registry.listTypes().map { it.type } shouldContainExactlyInAnyOrder listOf("CASE_FILE_EXCHANGE")
        }

        "the built-in exchange descriptors are the two file-exchange types, flagged builtIn with no config" {
            val descriptors = ExchangeIntegrationTypes.builtInDescriptors()

            descriptors.map { it.type } shouldContainExactlyInAnyOrder
                listOf(ExchangeIntegrationTypes.CASE, ExchangeIntegrationTypes.NAMESPACE)
            descriptors.all { it.builtIn } shouldBe true
            descriptors.all { it.configSchema == null } shouldBe true
        }
    })
