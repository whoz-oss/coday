package io.whozoss.agentos.redirect

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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

    fun context(userId: UUID? = null) = ToolContext(
        namespaceId = namespaceId,
        userId = userId,
        userExternalId = null,
        caseEvents = emptyList(),
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

    "provideTools returns empty list when resolver returns no agents" {
        val plugin = RedirectToolPlugin { _, _, _ -> emptyList() }

        val tools = plugin.provideTools(config = null, context = context(userId = userId))

        tools.shouldBeEmpty()
    }

    "provideTools returns empty list when context has no namespaceId" {
        val plugin = RedirectToolPlugin { _, _, _ -> listOf(agentConfig("AgentA")) }

        // ToolContext requires namespaceId — pass null context to simulate missing namespace
        val tools = plugin.provideTools(config = null, context = null)

        tools.shouldBeEmpty()
    }
})
