package io.whozoss.agentos.queryUser

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentInterrupt
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

    "configSchema is a non-null object schema declaring allowedQuestionTypes" {
        val schema = QueryUserToolPlugin().configSchema
        schema.shouldNotBeNull()
        schema["type"].asText() shouldBe "object"
        schema["properties"]["allowedQuestionTypes"].shouldNotBeNull()
        schema["properties"]["allowedQuestionTypes"]["items"]["enum"].map { it.asText() } shouldBe
            listOf("FREE_TEXT", "SINGLE_CHOICE", "OPEN_CHOICE")
    }

    "configSchema does not expose OAUTH_AUTHORIZE" {
        val schema = QueryUserToolPlugin().configSchema
        val enumValues = schema["properties"]["allowedQuestionTypes"]["items"]["enum"].map { it.asText() }
        enumValues shouldNotContain "OAUTH_AUTHORIZE"
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

    // -------------------------------------------------------------------------
    // allowedQuestionTypes tolerance (config absent / empty / unknown value)
    //
    // Behaviour is asserted indirectly through execute(), since allowedQuestionTypes
    // is a private implementation detail of QueryUserTool (see QueryUserToolSpec for
    // direct construction-based coverage of the same tolerance).
    // -------------------------------------------------------------------------

    /**
     * Returns whether [tool] accepts an OPEN_CHOICE call.
     *
     * Note the inversion: on the **happy path** `execute` does not return at all, it throws
     * [AgentInterrupt.AwaitAnswer] -- a control-flow signal handing the turn back to the
     * orchestrator, not an error. Reaching the line after the call therefore means the call was
     * *refused*, and the only way to observe acceptance is to catch the interrupt.
     *
     * Asserting on `result.success` here would be silently wrong: it can never be true, so every
     * "accepted" case would fail on an uncaught exception and every "refused" case would pass for
     * the wrong reason.
     */
    suspend fun acceptsOpenChoice(tool: QueryUserTool): Boolean =
        try {
            val result = tool.execute(
                QueryUserTool.Input(question = "q", options = listOf("A", "B"), allowCustomAnswer = true),
                mockk(relaxed = true),
            )
            // Refused: the only non-throwing path is ToolExecutionResult.error.
            result.success shouldBe false
            false
        } catch (_: AgentInterrupt.AwaitAnswer) {
            true
        }

    "provideTools allows all three types when config is null" {
        val tool = QueryUserToolPlugin().provideTools(config = null, configName = null, context = context()).first() as QueryUserTool
        acceptsOpenChoice(tool) shouldBe true
    }

    "provideTools allows all three types when allowedQuestionTypes is an empty array" {
        val config = jacksonObjectMapper().readTree("""{"allowedQuestionTypes": []}""")
        val tool = QueryUserToolPlugin().provideTools(config = config, configName = null, context = context()).first() as QueryUserTool
        acceptsOpenChoice(tool) shouldBe true
    }

    "provideTools ignores unknown values and falls back to all three types when the set would be empty" {
        val config = jacksonObjectMapper().readTree("""{"allowedQuestionTypes": ["BOGUS", "OAUTH_AUTHORIZE"]}""")
        val tool = QueryUserToolPlugin().provideTools(config = config, configName = null, context = context()).first() as QueryUserTool
        acceptsOpenChoice(tool) shouldBe true
    }

    "provideTools keeps only the known, valid values and ignores the rest" {
        val config = jacksonObjectMapper().readTree("""{"allowedQuestionTypes": ["FREE_TEXT", "BOGUS"]}""")
        val tool = QueryUserToolPlugin().provideTools(config = config, configName = null, context = context()).first() as QueryUserTool
        acceptsOpenChoice(tool) shouldBe false
    }
})
