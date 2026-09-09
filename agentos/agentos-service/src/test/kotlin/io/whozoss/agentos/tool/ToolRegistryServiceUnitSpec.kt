package io.whozoss.agentos.tool

import com.fasterxml.jackson.databind.JsonNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.exchange.ExchangeToolsConfigProperties
import io.whozoss.agentos.integrationConfig.CompositeIntegrationTypeRegistry
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import org.pf4j.PluginManager

/**
 * Unit spec for the built-in exchange type registration in [ToolRegistryService].
 *
 * The two [ExchangeToolsConfigProperties] flags are passed as adjacent Booleans into
 * [ExchangeIntegrationTypes.builtInDescriptors], and the client labels the "platform default"
 * state of each toggle from the resulting descriptor — so a silent argument swap or a wrong
 * property reference would mislabel the UI without failing anywhere else.
 */
class ToolRegistryServiceUnitSpec :
    StringSpec({

        fun makePlugin(integrationType: String): ToolPlugin =
            object : ToolPlugin {
                override val integrationType = integrationType
                override val configSchema: JsonNode? = null

                override fun provideTools(
                    config: JsonNode?,
                    configName: String?,
                    context: ToolContext?,
                ): List<StandardTool<*>> = emptyList()
            }

        fun initializedRegistry(
            plugins: List<ToolPlugin>,
            properties: ExchangeToolsConfigProperties,
        ): CompositeIntegrationTypeRegistry {
            val pluginManager = mockk<PluginManager>(relaxed = true)
            every { pluginManager.getExtensions(ToolPlugin::class.java) } returns plugins
            every { pluginManager.whichPlugin(any()) } returns null
            val registry = CompositeIntegrationTypeRegistry()
            ToolRegistryService(
                pluginManager = pluginManager,
                integrationTypeRegistry = registry,
                exchangeToolsConfigProperties = properties,
            ).initialize()
            return registry
        }

        "each exchange descriptor carries its own platform default from the config properties" {
            // Asymmetric flags: a swap of the two Boolean arguments would make both assertions fail.
            val registry =
                initializedRegistry(
                    plugins = listOf(makePlugin(ExchangeIntegrationTypes.FILE_ACCESS)),
                    properties =
                        ExchangeToolsConfigProperties(
                            caseEnabledByDefault = true,
                            namespaceEnabledByDefault = false,
                        ),
                )

            val case = registry.findByType(ExchangeIntegrationTypes.CASE)
            val namespace = registry.findByType(ExchangeIntegrationTypes.NAMESPACE)
            case shouldNotBe null
            namespace shouldNotBe null
            case!!.enabledByDefault shouldBe true
            namespace!!.enabledByDefault shouldBe false
        }

        "no built-in exchange descriptor is registered when the FILE_ACCESS plugin is absent" {
            // Some other plugin being loaded must not be enough: the gate is FILE_ACCESS
            // specifically, and the platform defaults cannot resurrect the descriptors.
            val registry =
                initializedRegistry(
                    plugins = listOf(makePlugin("DATETIME")),
                    properties =
                        ExchangeToolsConfigProperties(
                            caseEnabledByDefault = true,
                            namespaceEnabledByDefault = true,
                        ),
                )

            registry.listTypes().shouldBeEmpty()
        }
    })
