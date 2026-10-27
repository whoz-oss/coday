package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
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
 * MockK type-inference issues on `ChatClient.prompt(Prompt)`.
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

    /**
     * Builds a [LoopConfigGenerationService] backed by a [ChatClientProvider] whose
     * [ChatClient] returns [llmResponse] when called.
     *
     * Uses the chained MockK pattern from AgentIntentionGeneratorSpec:
     * `every { chatClient.prompt(any<Prompt>()).call().content() } returns llmResponse`
     */
    fun service(llmResponse: String?): LoopConfigGenerationService {
        val chatClient = mockk<ChatClient>(relaxed = true)
        every { chatClient.prompt(any<Prompt>()).call().content() } returns llmResponse

        val provider = mockk<ChatClientProvider>()
        every { provider.getChatClient(any(), any(), null, null) } returns chatClient

        return LoopConfigGenerationService(provider, objectMapper)
    }

    // -------------------------------------------------------------------------
    // Unknown tool
    // -------------------------------------------------------------------------

    "returns loopConfig=null with descriptive rawResponse when searchToolName is not among agent tools" {
        val svc = service("irrelevant")
        val def = definition(listOf(mockTool("OtherTool")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "find me something")

        result.loopConfig.shouldBeNull()
        result.rawResponse shouldContain "SearchTalents"
        result.rawResponse shouldContain "OtherTool"
    }

    "does not call the LLM when the tool is unknown" {
        val provider = mockk<ChatClientProvider>()
        val svc = LoopConfigGenerationService(provider, objectMapper)
        val def = definition(emptyList())

        svc.generate(def, searchToolName = "SearchTalents", description = "find me something")

        verify(exactly = 0) { provider.getChatClient(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Happy path — clean JSON
    // -------------------------------------------------------------------------

    "returns the full loopConfig structure when the LLM returns clean JSON" {
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
    }

    "loopConfig with extra whitespace around the JSON is parsed correctly" {
        val llmJson = "  \n  {\"startDatePeriod\":[\"LAST_MONTH\"],\"resolveTargets\":[\"MANAGER\"]}  \n  "
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "managers last month")

        result.loopConfig.shouldNotBeNull()
        result.warning.shouldBeNull()
        result.loopConfig!!["search"]["params"]["resolveTargets"][0].asText() shouldBe "MANAGER"
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

    "returns loopConfig=null with rawResponse when LLM output is not parseable JSON" {
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

    "returns loopConfig with a warning when the LLM generates an empty JSON object" {
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

    "returns loopConfig=null with warning when the LLM returns null content" {
        val svc = service(null)
        val def = definition(listOf(mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = "SearchTalents", description = "something")

        result.loopConfig.shouldBeNull()
        result.warning.shouldNotBeNull()
    }

    // -------------------------------------------------------------------------
    // Auto-select mode (searchToolName = null)
    // -------------------------------------------------------------------------

    "auto-select: returns loopConfig=null immediately when no tools are available" {
        val provider = mockk<ChatClientProvider>()
        val svc = LoopConfigGenerationService(provider, objectMapper)
        val def = definition(emptyList())

        val result = svc.generate(def, searchToolName = null, description = "find something")

        result.loopConfig.shouldBeNull()
        result.rawResponse.shouldNotBeNull()
        verify(exactly = 0) { provider.getChatClient(any(), any(), any(), any()) }
    }

    "auto-select: returns full loopConfig when LLM picks a valid tool" {
        val llmJson = """{"tool":"SearchTasks","params":{"startDatePeriod":["THIS_WEEK"],"resolveTargets":["OWNER"]}}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTasks"), mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = null, description = "owners of tasks this week")

        result.loopConfig.shouldNotBeNull()
        result.rawResponse.shouldBeNull()
        val search = result.loopConfig!!["search"]
        search["tool"].asText() shouldBe "SearchTasks"
        search["params"]["resolveTargets"][0].asText() shouldBe "OWNER"
    }

    "auto-select: returns loopConfig=null when LLM response is missing the 'tool' field" {
        val llmJson = """{"params":{"startDatePeriod":["THIS_WEEK"]}}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTasks")))

        val result = svc.generate(def, searchToolName = null, description = "something")

        result.loopConfig.shouldBeNull()
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "tool"
    }

    "auto-select: returns loopConfig=null when LLM picks a tool not in the available list" {
        val llmJson = """{"tool":"SearchProjects","params":{}}"""
        val svc = service(llmJson)
        val def = definition(listOf(mockTool("SearchTasks"), mockTool("SearchTalents")))

        val result = svc.generate(def, searchToolName = null, description = "something")

        result.loopConfig.shouldBeNull()
        result.warning.shouldNotBeNull()
        result.warning!! shouldContain "SearchProjects"
    }

    "auto-select: extracts tool+params from a code-fenced LLM response" {
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
    }

    "auto-select: returns loopConfig=null with warning when LLM returns null content" {
        val svc = service(null)
        val def = definition(listOf(mockTool("SearchTasks")))

        val result = svc.generate(def, searchToolName = null, description = "something")

        result.loopConfig.shouldBeNull()
        result.warning.shouldNotBeNull()
    }
})
