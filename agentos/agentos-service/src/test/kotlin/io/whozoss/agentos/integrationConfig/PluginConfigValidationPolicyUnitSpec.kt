package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolPlugin
import io.whozoss.agentos.tool.ToolRegistryService
import org.springframework.beans.factory.ObjectProvider

class PluginConfigValidationPolicyUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper()
        val refusal = "Google Workspace configuration refused: INVALID_CONFIG at drive.blockedSharedDriveIds: " +
            "a shared drive ID cannot be both allowed and blocked"

        fun plugin(fail: Exception?) =
            object : ToolPlugin {
                override val integrationType = "GOOGLE_WORKSPACE"
                override val configSchema: JsonNode? = null

                override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
                    context shouldBe null
                    if (fail != null) throw fail
                    return emptyList()
                }
            }

        fun policy(plugin: ToolPlugin?): PluginConfigValidationPolicy {
            val registry = mockk<ToolRegistryService> { every { findPlugin("GOOGLE_WORKSPACE") } returns plugin }
            val provider = mockk<ObjectProvider<ToolRegistryService>> { every { getIfAvailable() } returns registry }
            return PluginConfigValidationPolicy(provider)
        }

        val config = IntegrationConfig(name = "GOOGLE", integrationType = "GOOGLE_WORKSPACE", parameters = mapper.readTree("{}"))

        "only the opted-in pure plugins are validated at save time" {
            policy(null).supports("GOOGLE_WORKSPACE") shouldBe true
            policy(null).supports("MCP_HTTP") shouldBe false
            policy(null).supports("HTTP_API") shouldBe false
        }

        "a plugin refusal becomes a 422 carrying the plugin's value-free message" {
            shouldThrow<UnprocessableEntityException> { policy(plugin(IllegalArgumentException(refusal))).validate(config) }
                .message shouldBe refusal
        }

        "an unexpected plugin error is refused without echoing its message" {
            shouldThrow<UnprocessableEntityException> { policy(plugin(IllegalStateException("secret=XYZ"))).validate(config) }
                .message shouldBe "Invalid GOOGLE_WORKSPACE configuration"
        }

        "a valid configuration and a missing plugin are accepted" {
            shouldNotThrowAny { policy(plugin(null)).validate(config) }
            shouldNotThrowAny { policy(null).validate(config) }
        }
    })
