package io.whozoss.agentos.agentosPlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class AgentosToolPluginUnitSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val noopListAgents: (UUID, UUID?, Boolean) -> List<AgentConfig>? = { _, _, _ -> emptyList() }
    val noopGetAgent: (UUID, UUID?, String) -> AgentConfig? = { _, _, _ -> null }
    val noopCreateAgent: (UUID, UUID?, CreateAgentTool.Input) -> AgentConfig? = { _, _, _ -> null }
    val noopUpdateAgent: (UUID, UUID?, UpdateAgentTool.Input) -> AgentConfig? = { _, _, _ -> null }
    val noopEnableAgent: (UUID, UUID?, String) -> AgentConfig? = { _, _, _ -> null }
    val noopDisableAgent: (UUID, UUID?, String) -> AgentConfig? = { _, _, _ -> null }

    fun plugin() = AgentosToolPlugin(
        listAgents = noopListAgents,
        getAgent = noopGetAgent,
        createAgent = noopCreateAgent,
        updateAgent = noopUpdateAgent,
        enableAgent = noopEnableAgent,
        disableAgent = noopDisableAgent,
    )

    fun context() = ToolContext(
        namespaceId = namespaceId,
        userId = null,
        userExternalId = null,
        caseEvents = emptyList(),
    )

    // -------------------------------------------------------------------------
    // Integration type and config schema
    // -------------------------------------------------------------------------

    "integrationType is AGENTOS" {
        plugin().integrationType shouldBe "AGENTOS"
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
        tools.map { it.name }.toSet() shouldBe setOf(
            "ListAgents", "GetAgent", "CreateAgent", "UpdateAgent", "EnableAgent", "DisableAgent"
        )
    }

    "propagates configName as prefix to all tool names" {
        val tools = plugin().provideTools(config = null, configName = "AGENTOS", context = context())
        tools.map { it.name }.toSet() shouldBe setOf(
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
        val config = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree("{}")
        plugin().provideTools(config = config, context = context()) shouldHaveSize 6
    }
})
