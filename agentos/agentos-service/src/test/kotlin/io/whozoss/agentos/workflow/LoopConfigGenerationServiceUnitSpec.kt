package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import kotlinx.coroutines.Dispatchers
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

/**
 * Unit tests for [LoopConfigGenerationService].
 *
 * The ChatClient chain is mocked using the same pattern as AgentIntentionGeneratorSpec:
 * `every { chatClient.prompt(any<Prompt>()).call().content() } returns "..."`
 *
 * This avoids the need to break the chain into multiple `every` blocks, which triggers
 * MockK type-inference issues on `ChatClient.prompt(Prompt)`. The service runs on a real [StructuredLlmClient]
 * (whose own behaviour is covered by its spec), so what is checked here is the generation logic on top of it.
 */
class LoopConfigGenerationServiceUnitSpec : StringSpec({

    val objectMapper = ObjectMapper().registerKotlinModule()

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a mock [StandardTool] whose [name] and [inputSchema] are set.
     * Using `StandardTool<Any>` rather than `StandardTool<*>` avoids MockK
     * type-parameter inference failures on generic star projections.
     */
    fun mockTool(
        name: String,
        schema: String = """{"type":"object","properties":{"startDatePeriod":{"type":"array"},"resolveTargets":{"type":"array"}}}""",
    ): StandardTool<Any> =
        mockk<StandardTool<Any>>().also {
            every { it.name } returns name
            every { it.inputSchema } returns schema
        }

    /**
     * Builds a minimal [ResolvedAgentDefinition] carrying the given [tools].
     */
    fun definition(tools: List<StandardTool<Any>>): ResolvedAgentDefinition {
        val nsId = UUID.randomUUID()
        return ResolvedAgentDefinition(
            agentConfigId = UUID.randomUUID(),
            name = "TestLoopAgent",
            systemPrompt = null,
            instructions = null,
            resolvedModelApiName = "claude-3-5-haiku",
            resolvedProviderName = "anthropic",
            resolvedModelId = UUID.randomUUID(),
            resolvedProviderId = UUID.randomUUID(),
            resolvedModel =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = UUID.randomUUID(),
                    namespaceId = nsId,
                    apiModelName = "claude-3-5-haiku",
                ),
            resolvedProvider =
                AiProvider(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = nsId,
                    name = "anthropic",
                    apiType = io.whozoss.agentos.sdk.aiProvider.AiApiType.Anthropic,
                    baseUrl = "https://api.anthropic.com",
                    apiKey = "sk-ant-test",
                ),
            tools = tools,
            executionMode = ExecutionMode.LOOP,
            namespaceId = nsId,
            userId = null,
        )
    }

    /** Prompt sent to the LLM by the last call of a service built by [service]. */
    val sentPrompt = slot<Prompt>()

    /**
     * Builds a [LoopConfigGenerationService] backed by a [ChatClientProvider] whose
     * [ChatClient] returns [llmResponse] when called.
     */
    fun service(llmResponse: String?): LoopConfigGenerationService {
        val chatClient = mockk<ChatClient>(relaxed = true)
        every { chatClient.prompt(capture(sentPrompt)).call().content() } returns llmResponse

        val provider = mockk<ChatClientProvider>()
        every { provider.getChatClient(any(), any(), null, null) } returns chatClient

        return LoopConfigGenerationService(StructuredLlmClient(provider, objectMapper, Dispatchers.Unconfined), objectMapper)
    }

    fun userMessageOf(prompt: Prompt): String = prompt.instructions.last().text

    fun systemMessageOf(prompt: Prompt): String = prompt.instructions.first().text

    // -------------------------------------------------------------------------
    // Unknown tool
    // -------------------------------------------------------------------------

    "explains in the warning, not in rawResponse, that searchToolName is not among agent tools" {
        val svc = service("irrelevant")
        val def = definition(listOf(mockTool("OtherTool")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "find me something")

        result.loopConfig.shouldBeNull()
        result.rawResponse.shouldBeNull()
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "SearchTalents"
        result.warning!! shouldContain "OtherTool"
    }

    "does not call the LLM when the tool is unknown" {
        val provider = mockk<ChatClientProvider>()
        val svc = LoopConfigGenerationService(StructuredLlmClient(provider, objectMapper, Dispatchers.Unconfined), objectMapper)
        val def = definition(emptyList())

        svc.generate(def, searchToolName = "SearchTalents", description = "find me something")

        verify(exactly = 0) { provider.getChatClient(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Happy path — clean JSON
    // -------------------------------------------------------------------------

    "returns the search block when the LLM returns clean JSON" {
        val llmJson = """{"startDatePeriod":["THIS_WEEK"],"resolveTargets":["OWNER"],"next":null}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "owners of tasks starting this week")

        result.loopConfig.shouldNotBeNull()
        result.rawResponse.shouldBeNull()
        result.warning.shouldBeNull()
        val search = result.loopConfig!!["search"]
        search["tool"].asText() shouldBe "SearchTalents"
        search["params"]["resolveTargets"][0].asText() shouldBe "OWNER"
        search["params"]["startDatePeriod"][0].asText() shouldBe "THIS_WEEK"
        // the output is the search block only: no act block
        result.loopConfig!!.has("act") shouldBe false
    }

    "search block with extra whitespace around the JSON is parsed correctly" {
        val llmJson = "  \n  {\"startDatePeriod\":[\"LAST_MONTH\"],\"resolveTargets\":[\"MANAGER\"]}  \n  "
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "managers last month")

        result.loopConfig.shouldNotBeNull()
        result.warning.shouldBeNull()
        result.loopConfig!!["search"]["params"]["resolveTargets"][0].asText() shouldBe "MANAGER"
    }

    // -------------------------------------------------------------------------
    // Additional context
    // -------------------------------------------------------------------------

    "appends the additional context to the user message, in both modes" {
        val context = "Target agent: Caretaker (agentConfigId=abc-123)"
        val tool = mockTool("searchAgentUsers")

        service("{}").generate(definition(listOf(tool)), "searchAgentUsers", "my team", context)
        val singleToolMessage = userMessageOf(sentPrompt.captured)
        service("""{"tool":"searchAgentUsers","params":{}}""").generate(definition(listOf(tool)), null, "my team", context)
        val autoSelectMessage = userMessageOf(sentPrompt.captured)

        listOf(singleToolMessage, autoSelectMessage).forEach {
            it shouldContain "my team"
            it shouldContain "Additional context:\n$context"
        }
    }

    "adds no additional-context block without a context, and mentions it in both system prompts" {
        val tool = mockTool("searchAgentUsers")

        service("{}").generate(definition(listOf(tool)), "searchAgentUsers", "my team")
        userMessageOf(sentPrompt.captured) shouldNotContain "Additional context"
        systemMessageOf(sentPrompt.captured) shouldContain "Additional context"

        service("""{"tool":"searchAgentUsers","params":{}}""").generate(definition(listOf(tool)), null, "my team")
        userMessageOf(sentPrompt.captured) shouldNotContain "Additional context"
        systemMessageOf(sentPrompt.captured) shouldContain "Additional context"
    }

    // -------------------------------------------------------------------------
    // Fallback parse — code fence
    // -------------------------------------------------------------------------

    "extracts JSON from a markdown ```json code fence and sets a warning" {
        val llmResponse = """
            Here is the generated params:
            ```json
            {"startDatePeriod":["NEXT_MONTH"],"resolveTargets":["OWNER"]}
            ```
        """.trimIndent()
        val svc = service(llmResponse)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "next month owners")

        result.loopConfig.shouldNotBeNull()
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "code fence"
        result.rawResponse.shouldBeNull()
        result.loopConfig!!["search"]["params"]["resolveTargets"][0].asText() shouldBe "OWNER"
    }

    "extracts JSON from a bare ``` code fence and sets a warning" {
        val llmResponse = """
            ```
            {"startDatePeriod":["THIS_YEAR"],"resolveTargets":["PEER"]}
            ```
        """.trimIndent()
        val svc = service(llmResponse)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "peers this year")

        result.loopConfig.shouldNotBeNull()
        result.warning.shouldNotBeNull()
        result.loopConfig!!["search"]["params"]["resolveTargets"][0].asText() shouldBe "PEER"
    }

    // -------------------------------------------------------------------------
    // Parse failure
    // -------------------------------------------------------------------------

    "returns a null search block with the raw text in rawResponse and the reason in warning when the output is not JSON" {
        val llmResponse = "I cannot generate the params because the description is unclear."
        val svc = service(llmResponse)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "unclear request")

        result.loopConfig.shouldBeNull()
        result.rawResponse shouldBe llmResponse
        result.warning.shouldNotBeNull()
    }

    // -------------------------------------------------------------------------
    // Empty object
    // -------------------------------------------------------------------------

    "returns the search block with a warning when the LLM generates an empty JSON object" {
        val svc = service("{}")
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "find everything")

        result.loopConfig.shouldNotBeNull()
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "empty"
        result.rawResponse.shouldBeNull()
        result.loopConfig!!["search"]["tool"].asText() shouldBe "SearchTalents"
    }

    // -------------------------------------------------------------------------
    // Null LLM response
    // -------------------------------------------------------------------------

    "returns a null search block with a warning and no rawResponse when the LLM returns null content" {
        val svc = service(null)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "something")

        result.loopConfig.shouldBeNull()
        result.rawResponse.shouldBeNull()
        result.warning.shouldNotBeNull()
    }

    // -------------------------------------------------------------------------
    // Auto-select mode (searchToolName = null)
    // -------------------------------------------------------------------------

    "auto-select: returns a null search block immediately, explained by the warning, when no tools are available" {
        val provider = mockk<ChatClientProvider>()
        val svc = LoopConfigGenerationService(StructuredLlmClient(provider, objectMapper, Dispatchers.Unconfined), objectMapper)
        val def = definition(emptyList())

        val result = svc.generate(def, searchToolName = null, description = "find something")

        result.loopConfig.shouldBeNull()
        result.rawResponse.shouldBeNull()
        result.warning.shouldNotBeNull()
        verify(exactly = 0) { provider.getChatClient(any(), any(), any(), any()) }
    }

    "auto-select: returns the search block when LLM picks a valid tool" {
        val llmJson = """{"tool":"SearchTasks","params":{"startDatePeriod":["THIS_WEEK"],"resolveTargets":["OWNER"]}}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTasks"), mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = null, description = "owners of tasks this week")

        result.loopConfig.shouldNotBeNull()
        result.rawResponse.shouldBeNull()
        result.warning.shouldBeNull()
        val search = result.loopConfig!!["search"]
        search["tool"].asText() shouldBe "SearchTasks"
        search["params"]["resolveTargets"][0].asText() shouldBe "OWNER"
    }

    "auto-select: returns a null search block when LLM response is missing the 'tool' field" {
        val llmJson = """{"params":{"startDatePeriod":["THIS_WEEK"]}}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTasks")))

        val result = svc.generate(def, searchToolName = null, description = "something")

        result.loopConfig.shouldBeNull()
        result.rawResponse shouldBe llmJson
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "tool"
    }

    "auto-select: returns a null search block when LLM picks a tool not in the available list" {
        val llmJson = """{"tool":"SearchProjects","params":{}}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTasks"), mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = null, description = "something")

        result.loopConfig.shouldBeNull()
        result.rawResponse shouldBe llmJson
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "SearchProjects"
    }

    "auto-select: extracts tool+params from a code-fenced LLM response and warns about the fence" {
        val llmResponse = """
            ```json
            {"tool":"SearchTalents","params":{"resolveTargets":["MANAGER"]}}
            ```
        """.trimIndent()
        val svc = service(llmResponse)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = null, description = "managers")

        result.loopConfig.shouldNotBeNull()
        result.loopConfig!!["search"]["tool"].asText() shouldBe "SearchTalents"
        result.loopConfig!!["search"]["params"]["resolveTargets"][0].asText() shouldBe "MANAGER"
        // the warning is tied to the fence actually used, even though params was present
        result.warning!! shouldContain "code fence"
    }

    "auto-select: a missing params gives empty params and no code-fence warning when no fence was used" {
        // Regression: the fence warning used to be tied to params being missing, not to the fence fallback.
        val svc = service("""{"tool":"SearchTalents"}""")
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = null, description = "everyone")

        result.loopConfig.shouldNotBeNull()
        result.loopConfig!!["search"]["params"].size() shouldBe 0
        result.warning.shouldBeNull()
    }

    "auto-select: returns a null search block with a warning and no rawResponse when LLM returns null content" {
        val svc = service(null)
        val def = definition(listOf(mockTool("SearchTasks")))

        val result = svc.generate(def, searchToolName = null, description = "something")

        result.loopConfig.shouldBeNull()
        result.rawResponse.shouldBeNull()
        result.warning.shouldNotBeNull()
    }

    // -------------------------------------------------------------------------
    // Search candidates and tool lookup (the loop's own rule)
    // -------------------------------------------------------------------------

    "auto-select: the case starters are never offered as the search tool" {
        val svc = service("""{"tool":"USER_GROUP__searchAgentUsers","params":{}}""")
        val def =
            definition(
                listOf(
                    mockTool("USER_GROUP__searchAgentUsers"),
                    mockTool("CASE__FreeCaseStarterForUser"),
                    mockTool("CASE__FreeCaseStarter"),
                ),
            )

        svc.generate(def, searchToolName = null, description = "my team")

        val message = userMessageOf(sentPrompt.captured)
        message shouldContain "USER_GROUP__searchAgentUsers"
        message shouldNotContain "FreeCaseStarter"
    }

    "auto-select: an LLM that still names a case starter is refused, and an agent with only case starters has no search tool" {
        val starters = listOf(mockTool("CASE__FreeCaseStarterForUser"))

        val chosen =
            service("""{"tool":"CASE__FreeCaseStarterForUser","params":{}}""")
                .generate(definition(starters + mockTool("SearchTasks")), null, "something")
        val none = service("{}").generate(definition(starters), null, "something")

        chosen.loopConfig.shouldBeNull()
        chosen.warning!! shouldContain "CASE__FreeCaseStarterForUser"
        none.loopConfig.shouldBeNull()
        none.warning!! shouldContain "No search tools"
    }

    "single-tool: a bare name finds the prefixed tool, and the search block names the real tool" {
        val svc = service("""{"agentConfigId":"abc"}""")
        val def = definition(listOf(mockTool("USER_GROUP__searchAgentUsers")))

        val result = svc.generate(def, searchToolName = "searchAgentUsers", description = "my team")

        result.loopConfig!!["search"]["tool"].asText() shouldBe "USER_GROUP__searchAgentUsers"
    }

    "single-tool: an ambiguous name, or a case starter, is refused without calling the LLM" {
        val provider = mockk<ChatClientProvider>()
        val svc = LoopConfigGenerationService(StructuredLlmClient(provider, objectMapper, Dispatchers.Unconfined), objectMapper)

        val ambiguous =
            svc.generate(definition(listOf(mockTool("A__searchAgentUsers"), mockTool("B__searchAgentUsers"))), "searchAgentUsers", "x")
        val starter = svc.generate(definition(listOf(mockTool("CASE__FreeCaseStarterForUser"))), "FreeCaseStarterForUser", "x")

        ambiguous.loopConfig.shouldBeNull()
        ambiguous.warning!! shouldContain "ambiguous"
        starter.loopConfig.shouldBeNull()
        starter.warning!! shouldContain "not available"
        verify(exactly = 0) { provider.getChatClient(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Base parameters: kept as given, only enriched
    // -------------------------------------------------------------------------

    val baseParams = objectMapper.readTree("""{"agentConfigId": "d715a9da", "filters": {"status": "ACTIVE"}}""")

    "single-tool: the base parameters are shown to the LLM, and kept even when the LLM drops or changes them" {
        // the LLM drops agentConfigId, rewrites filters.status, and adds two criteria
        val svc = service("""{"filters": {"status": "ANY", "country": "FR"}, "startDatePeriod": ["THIS_WEEK"]}""")
        val def = definition(listOf(mockTool("searchAgentUsers")))

        val result = svc.generate(def, "searchAgentUsers", "French users, this week", baseParams = baseParams)

        userMessageOf(sentPrompt.captured) shouldContain "Base parameters"
        userMessageOf(sentPrompt.captured) shouldContain "d715a9da"
        result.loopConfig!!["search"]["params"] shouldBe
            objectMapper.readTree(
                """{"agentConfigId": "d715a9da", "filters": {"status": "ACTIVE", "country": "FR"}, "startDatePeriod": ["THIS_WEEK"]}""",
            )
    }

    "auto-select: the base parameters are kept in the params of the chosen tool" {
        val svc = service("""{"tool": "SearchTasks", "params": {"startDatePeriod": ["THIS_WEEK"]}}""")
        val def = definition(listOf(mockTool("SearchTasks"), mockTool("SearchTalents")))

        val result = svc.generate(def, null, "tasks this week", baseParams = baseParams)

        val params = result.loopConfig!!["search"]["params"]
        params["agentConfigId"].asText() shouldBe "d715a9da"
        params["filters"]["status"].asText() shouldBe "ACTIVE"
        params["startDatePeriod"][0].asText() shouldBe "THIS_WEEK"
    }

    "no base-parameters block without base parameters, or with an empty object, and both system prompts explain it" {
        val tool = mockTool("SearchTalents")

        listOf(null, objectMapper.createObjectNode()).forEach { empty ->
            service("{}").generate(definition(listOf(tool)), "SearchTalents", "x", baseParams = empty)
            userMessageOf(sentPrompt.captured) shouldNotContain "Base parameters"
            systemMessageOf(sentPrompt.captured) shouldContain "Base parameters"
        }
        service("""{"tool":"SearchTalents","params":{}}""").generate(definition(listOf(tool)), null, "x")
        systemMessageOf(sentPrompt.captured) shouldContain "Base parameters"
    }

    "enrich keeps every base value, merges nested objects, and combines arrays without losing or duplicating items" {
        val svc = service("{}")
        forAll(
            // the LLM adds a key
            row("""{"a": 1}""", """{"b": 2}""", """{"b": 2, "a": 1}"""),
            // the LLM changes a base scalar: the base wins
            row("""{"a": 1}""", """{"a": 9}""", """{"a": 1}"""),
            // the LLM nulls a base value: the base wins
            row("""{"a": {"x": 1}}""", """{"a": null}""", """{"a": {"x": 1}}"""),
            // nested objects are merged the same way
            row("""{"a": {"x": 1}}""", """{"a": {"x": 9, "y": 2}}""", """{"a": {"x": 1, "y": 2}}"""),
            // arrays are combined: base items first, then the new ones
            row("""{"a": ["X"]}""", """{"a": ["Y", "Z"]}""", """{"a": ["X", "Y", "Z"]}"""),
            // an item already in the base is not repeated, nor one the LLM repeats
            row("""{"a": ["X", "Y"]}""", """{"a": ["Y", "Z", "Z"]}""", """{"a": ["X", "Y", "Z"]}"""),
            // the LLM drops a base item: it is kept
            row("""{"a": ["X", "Y"]}""", """{"a": ["Z"]}""", """{"a": ["X", "Y", "Z"]}"""),
            // object items are compared as a whole
            row("""{"a": [{"k": 1}]}""", """{"a": [{"k": 1}, {"k": 2}]}""", """{"a": [{"k": 1}, {"k": 2}]}"""),
            // nested arrays are combined too
            row("""{"f": {"s": ["A"]}}""", """{"f": {"s": ["B"]}}""", """{"f": {"s": ["A", "B"]}}"""),
            // a type mismatch keeps the base value
            row("""{"a": ["X"]}""", """{"a": "Y"}""", """{"a": ["X"]}"""),
        ) { base, generated, expected ->
            svc.enrich(objectMapper.readTree(base), objectMapper.readTree(generated)) shouldBe objectMapper.readTree(expected)
        }
        svc.enrich(null, objectMapper.readTree("""{"a": 1}""")) shouldBe objectMapper.readTree("""{"a": 1}""")
    }

    "single-tool: a criterion added to a base list ends up next to the base value" {
        val svc = service("""{"startDatePeriod": ["NEXT_WEEK"]}""")
        val def = definition(listOf(mockTool("SearchTasks")))

        val result =
            svc.generate(def, "SearchTasks", "this week and next week", baseParams = objectMapper.readTree("""{"startDatePeriod": ["THIS_WEEK"]}"""))

        result.loopConfig!!["search"]["params"]["startDatePeriod"] shouldBe objectMapper.readTree("""["THIS_WEEK", "NEXT_WEEK"]""")
    }
})
