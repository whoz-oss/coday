package io.whozoss.agentos.queryUser

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

class QueryUserToolPluginSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()

    fun context(configName: String? = null) =
        ToolContext(
            namespaceId = namespaceId,
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
        )

    // -------------------------------------------------------------------------
    // integrationType
    // -------------------------------------------------------------------------

    "integrationType is QUERY_USER" {
        QueryUserToolPlugin().integrationType shouldBe "QUERY_USER"
    }

    // -------------------------------------------------------------------------
    // configSchema
    // -------------------------------------------------------------------------

    "configSchema is null (config-less plugin)" {
        QueryUserToolPlugin().configSchema shouldBe null
    }

    // -------------------------------------------------------------------------
    // provideTools
    // -------------------------------------------------------------------------

    "provideTools returns a single QueryUserTool" {
        val plugin = QueryUserToolPlugin()
        val tools = plugin.provideTools(config = null, configName = null, context = context())
        tools shouldHaveSize 1
        tools.first().shouldBeInstanceOf<QueryUserTool>()
    }

    "provideTools returns QueryUserTool with bare name when configName is null" {
        val plugin = QueryUserToolPlugin()
        val tools = plugin.provideTools(config = null, configName = null, context = context())
        tools.first().name shouldBe "queryUser"
    }

    "provideTools returns QueryUserTool with prefixed name when configName is provided" {
        val plugin = QueryUserToolPlugin()
        val tools = plugin.provideTools(config = null, configName = "MY_QUERY", context = context())
        tools.first().name shouldBe "MY_QUERY__queryUser"
    }

    "provideTools returns a single tool even when context is null" {
        // Unlike RedirectToolPlugin, QueryUserToolPlugin does not need the context
        // to resolve anything — it should return a tool regardless.
        val plugin = QueryUserToolPlugin()
        val tools = plugin.provideTools(config = null, configName = null, context = null)
        tools shouldHaveSize 1
    }
})
