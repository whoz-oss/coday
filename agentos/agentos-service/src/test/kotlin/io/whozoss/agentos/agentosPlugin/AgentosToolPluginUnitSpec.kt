package io.whozoss.agentos.agentosPlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class AgentosToolPluginUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()

        fun plugin() = AgentosAgentsToolPlugin(operations = mockk<AgentAdminOperations>(relaxed = true))

        fun context() =
            ToolContext(
                namespaceId = namespaceId,
                userId = null,
                userExternalId = null,
                caseEvents = emptyList(),
            )

        // -------------------------------------------------------------------------
        // Integration type and config schema
        // -------------------------------------------------------------------------

        "integrationType is AGENTOS_AGENTS" {
            plugin().integrationType shouldBe "AGENTOS_AGENTS"
        }

        "configSchema is a valid empty-properties JSON object" {
            val schema = plugin().configSchema
            schema.get("type").asText() shouldBe "object"
            schema.get("additionalProperties").asBoolean() shouldBe false
        }

        // -------------------------------------------------------------------------
        // context == null guard
        // -------------------------------------------------------------------------

        "provideTools returns empty list when context is null" {
            plugin().provideTools(config = null, context = null).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // Tool count and types
        // -------------------------------------------------------------------------

        "provideTools returns 6 tools when context is provided" {
            plugin().provideTools(config = null, context = context()) shouldHaveSize 6
        }

        "provideTools returns all 6 expected tool types" {
            val tools = plugin().provideTools(config = null, context = context())
            tools.filterIsInstance<ListAgentsTool>() shouldHaveSize 1
            tools.filterIsInstance<GetAgentTool>() shouldHaveSize 1
            tools.filterIsInstance<CreateAgentTool>() shouldHaveSize 1
            tools.filterIsInstance<UpdateAgentTool>() shouldHaveSize 1
            tools.filterIsInstance<EnableAgentTool>() shouldHaveSize 1
            tools.filterIsInstance<DisableAgentTool>() shouldHaveSize 1
        }

        // -------------------------------------------------------------------------
        // configName propagation
        // -------------------------------------------------------------------------

        "uses default tool names when configName is null" {
            val tools = plugin().provideTools(config = null, configName = null, context = context())
            tools.map { it.name }.toSet() shouldBe
                setOf(
                    "ListAgents",
                    "GetAgent",
                    "CreateAgent",
                    "UpdateAgent",
                    "EnableAgent",
                    "DisableAgent",
                )
        }

        "propagates configName as prefix to all tool names" {
            val tools = plugin().provideTools(config = null, configName = "AGENTOS", context = context())
            tools.map { it.name }.toSet() shouldBe
                setOf(
                    "AGENTOS__ListAgents",
                    "AGENTOS__GetAgent",
                    "AGENTOS__CreateAgent",
                    "AGENTOS__UpdateAgent",
                    "AGENTOS__EnableAgent",
                    "AGENTOS__DisableAgent",
                )
        }

        // -------------------------------------------------------------------------
        // config node is ignored (config-less plugin)
        // -------------------------------------------------------------------------

        "provideTools ignores non-null config node (config-less plugin)" {
            val config =
                com.fasterxml.jackson.module.kotlin
                    .jacksonObjectMapper()
                    .readTree("{}")
            plugin().provideTools(config = config, context = context()) shouldHaveSize 6
        }
    })
