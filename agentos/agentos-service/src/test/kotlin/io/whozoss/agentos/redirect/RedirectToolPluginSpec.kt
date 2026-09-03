package io.whozoss.agentos.redirect

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Unit tests for [RedirectToolPlugin].
 *
 * Verifies that the [agentResolver] lambda is called with the correct arguments
 * and that the resulting [RedirectTool] contains only the eligible agents.
 */
class RedirectToolPluginSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val userId: UUID = UUID.randomUUID()

    fun agentConfig(name: String, description: String? = null) = AgentConfig(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        namespaceId = namespaceId,
        name = name,
        description = description,
    )

    fun context(userId: UUID? = null, agentName: String? = null) = ToolContext(
        namespaceId = namespaceId,
        userId = userId,
        userExternalId = null,
        caseEvents = emptyList(),
        agentName = agentName,
    )

    // -------------------------------------------------------------------------
    // userId propagation
    // -------------------------------------------------------------------------

    "provideTools passes userId from context to agentResolver" {
        var capturedUserId: UUID? = UUID.randomUUID() // sentinel — must be overwritten
        val plugin = RedirectToolPlugin { _, uid, _ ->
            capturedUserId = uid
            emptyList()
        }

        plugin.provideTools(config = null, context = context(userId = userId))

        capturedUserId shouldBe userId
    }

    "provideTools passes null userId when context has no userId" {
        var capturedUserId: UUID? = UUID.randomUUID() // sentinel
        val plugin = RedirectToolPlugin { _, uid, _ ->
            capturedUserId = uid
            emptyList()
        }

        plugin.provideTools(config = null, context = context(userId = null))

        capturedUserId shouldBe null
    }

    "provideTools returns empty list when context is null" {
        // context == null means no namespaceId: the plugin short-circuits before calling
        // agentResolver, so the resolver is never invoked and an empty list is returned.
        var resolverCalled = false
        val plugin = RedirectToolPlugin { _, _, _ ->
            resolverCalled = true
            emptyList()
        }

        val tools = plugin.provideTools(config = null, context = null)

        tools.shouldBeEmpty()
        resolverCalled shouldBe false
    }

    // -------------------------------------------------------------------------
    // Eligible agents
    // -------------------------------------------------------------------------

    "provideTools returns a RedirectTool with eligible agents from resolver" {
        val agents = listOf(
            agentConfig("AgentA", "Does A"),
            agentConfig("AgentB", "Does B"),
        )
        val plugin = RedirectToolPlugin { _, _, _ -> agents }

        val tools = plugin.provideTools(config = null, context = context(userId = userId))

        tools shouldHaveSize 1
        val tool = tools.first() as RedirectTool
        tool.eligibleAgents.map { it.name } shouldBe listOf("AgentA", "AgentB")
    }

    // -------------------------------------------------------------------------
    // Integration propagation
    // -------------------------------------------------------------------------

    "provideTools maps AgentConfig.integrations to EligibleAgent.integrations" {
        val agents = listOf(
            AgentConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = "AgentA",
                description = "Does A",
                integrations = mapOf(
                    "JIRA" to listOf("GetIssue", "PostComment"),
                    "FILES" to null,
                ),
            ),
        )
        val plugin = RedirectToolPlugin { _, _, _ -> agents }

        val tool = plugin.provideTools(config = null, context = context(userId = userId)).first() as RedirectTool
        val eligible = tool.eligibleAgents.first()

        eligible.integrations shouldBe listOf(
            RedirectTool.Integration(name = "JIRA", allowedTools = listOf("GetIssue", "PostComment")),
            RedirectTool.Integration(name = "FILES", allowedTools = null),
        )
    }

    "provideTools produces empty integrations list when AgentConfig.integrations is null" {
        val agents = listOf(
            AgentConfig(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = "AgentA",
                description = null,
                integrations = null,
            ),
        )
        val plugin = RedirectToolPlugin { _, _, _ -> agents }

        val tool = plugin.provideTools(config = null, context = context(userId = userId)).first() as RedirectTool
        tool.eligibleAgents.first().integrations shouldBe emptyList()
    }

    "provideTools returns a RedirectTool with no-agents description when resolver returns no agents" {
        val plugin = RedirectToolPlugin { _, _, _ -> emptyList() }

        val tools = plugin.provideTools(config = null, context = context(userId = userId))

        tools shouldHaveSize 1
        val tool = tools.first() as RedirectTool
        tool.eligibleAgents.shouldBeEmpty()
        tool.description shouldBe
            "No other agents are currently available for redirection. Do not attempt to delegate — handle the request yourself or inform the user that no other agent can address it."
    }

    // -------------------------------------------------------------------------
    // Self-exclusion
    // -------------------------------------------------------------------------

    "provideTools excludes the calling agent from eligible agents" {
        val agents = listOf(
            agentConfig("AgentA", "Does A"),
            agentConfig("AgentB", "Does B"),
        )
        val plugin = RedirectToolPlugin { _, _, _ -> agents }

        val tool = plugin.provideTools(config = null, context = context(agentName = "AgentA")).first() as RedirectTool
        tool.eligibleAgents.map { it.name } shouldBe listOf("AgentB")
    }

    "provideTools returns a RedirectTool with no-agents description when calling agent is the only eligible agent" {
        val agents = listOf(agentConfig("AgentA", "Does A"))
        val plugin = RedirectToolPlugin { _, _, _ -> agents }

        val tools = plugin.provideTools(config = null, context = context(agentName = "AgentA"))
        tools shouldHaveSize 1
        val tool = tools.first() as RedirectTool
        tool.eligibleAgents.shouldBeEmpty()
    }

    "provideTools does not exclude anything when context has no agentName" {
        val agents = listOf(
            agentConfig("AgentA", "Does A"),
            agentConfig("AgentB", "Does B"),
        )
        val plugin = RedirectToolPlugin { _, _, _ -> agents }

        val tool = plugin.provideTools(config = null, context = context(agentName = null)).first() as RedirectTool
        tool.eligibleAgents.map { it.name } shouldBe listOf("AgentA", "AgentB")
    }

    "provideTools returns empty list when context has no namespaceId" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }

        // ToolContext requires namespaceId — pass null context to simulate missing namespace
        val tools = plugin.provideTools(config = null, context = null)

        tools.shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // WhatsNext tool
    // -------------------------------------------------------------------------

    "provideTools returns only RedirectTool when config has no guideline" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }
        val config = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            .readTree("""{"agents":["*"]}""")

        val tools = plugin.provideTools(config = config, context = context(userId = userId))

        tools shouldHaveSize 1
        tools.first() shouldBe tools.filterIsInstance<RedirectTool>().first()
    }

    "provideTools returns only RedirectTool when config is null" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }

        val tools = plugin.provideTools(config = null, context = context(userId = userId))

        tools shouldHaveSize 1
        tools.first() shouldBe tools.filterIsInstance<RedirectTool>().first()
    }

    "provideTools returns RedirectTool and WhatsNextTool when guideline is present" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }
        val config = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(
            """{"agents":["*"],"guideline":"When done, redirect to TRSharing."}"""
        )

        val tools = plugin.provideTools(config = config, context = context(userId = userId))

        tools shouldHaveSize 2
        tools.filterIsInstance<RedirectTool>() shouldHaveSize 1
        tools.filterIsInstance<WhatsNextTool>() shouldHaveSize 1
    }

    "provideTools WhatsNextTool carries the guideline from config" {
        val guideline = "When done, redirect to TRSharing."
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }
        val config = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(
            """{"guideline":"$guideline"}"""
        )

        val tools = plugin.provideTools(config = config, context = context(userId = userId))
        val whatsNext = tools.filterIsInstance<WhatsNextTool>().first()

        // execute returns a JSON-wrapped guideline: {"guideline": "..."}
        val result = kotlinx.coroutines.runBlocking {
            whatsNext.execute(null, mockk(relaxed = true))
        }
        result.success shouldBe true
        val parsed = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(result.output)
        parsed.get("guideline").asText() shouldBe guideline
    }

    "provideTools does not add WhatsNextTool when guideline is blank" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }
        val config = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(
            """{"guideline":"   "}"""
        )

        val tools = plugin.provideTools(config = config, context = context(userId = userId))

        tools shouldHaveSize 1
        tools.filterIsInstance<WhatsNextTool>().shouldBeEmpty()
    }

    "provideTools WhatsNextTool name uses configName prefix" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }
        val config = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(
            """{"guideline":"some guideline"}"""
        )

        val tools = plugin.provideTools(
            config = config,
            configName = "REDIRECT_all",
            context = context(userId = userId),
        )
        val whatsNext = tools.filterIsInstance<WhatsNextTool>().first()

        whatsNext.name shouldBe "REDIRECT_all__whatsNext"
    }
})
