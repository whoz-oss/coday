package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.aiModel.ModelResolver
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.generation.StructuredLlmResult
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.scheduledPrompt.FlowConfigurationPreset
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedField
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldSource
import java.util.UUID

/** Unit tests for [FlowConfigurationPresetSelector]; the LLM is mocked at [StructuredLlmClient]. */
class FlowConfigurationPresetSelectorUnitSpec :
    StringSpec({

        val objectMapper: ObjectMapper = jacksonObjectMapper()
        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val description = "Every Monday, ask the bench talents for news"

        val modelResolver = mockk<ModelResolver>()
        val structuredLlmClient = mockk<StructuredLlmClient>()
        val selector = FlowConfigurationPresetSelector(modelResolver, structuredLlmClient)

        fun json(text: String): JsonNode = objectMapper.readTree(text)

        val stories = FlowConfigurationPreset("Stories", json("""{"search": {"tool": "searchStories"}}"""))
        val bench = FlowConfigurationPreset("Bench", json("""{"search": {"tool": "searchBench"}}"""))
        val presets = listOf(stories, bench)

        fun answer(text: String) = StructuredLlmResult(json = objectMapper.readTree(text), rawText = text)

        fun llmReturns(result: StructuredLlmResult) {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns result
        }

        beforeTest {
            clearAllMocks()
            every { modelResolver.resolve(namespaceId, userId) } returns
                (mockk<AiModel>(relaxed = true) to mockk<AiProvider>(relaxed = true))
        }

        "a single preset is chosen without an LLM call" {
            val outcome = selector.choose(namespaceId, userId, description, listOf(stories))

            outcome.preset shouldBe stories
            outcome.decision.shouldNotBeNull().let {
                it.field shouldBe GeneratedField.PRESET
                it.source shouldBe GeneratedFieldSource.INFERRED
                it.value shouldBe "Stories"
                it.rationale shouldBe "only preset available"
            }
            outcome.warnings shouldBe emptyList()
            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
        }

        "several presets: one LLM call with the presets and the description, and the answer is matched case-insensitively" {
            val system = slot<String>()
            val user = slot<String>()
            coEvery { structuredLlmClient.call(any(), any(), capture(system), capture(user)) } returns
                answer("""{"presetName": "bench", "rationale": "bench talents"}""")

            val outcome = selector.choose(namespaceId, userId, description, presets)

            outcome.preset shouldBe bench
            outcome.decision.shouldNotBeNull().let {
                it.value shouldBe "Bench"
                it.source shouldBe GeneratedFieldSource.INFERRED
                it.rationale shouldBe "bench talents"
            }
            outcome.warnings shouldBe emptyList()
            outcome.unusableRawText.shouldBeNull()
            system.captured shouldBe FlowConfigurationPresetSelector.SYSTEM_PROMPT
            user.captured shouldContain "Stories"
            user.captured shouldContain "searchStories"
            user.captured shouldContain "Bench"
            user.captured shouldContain "searchBench"
            user.captured shouldContain description
            coVerify(exactly = 1) { structuredLlmClient.call(any(), any(), any(), any()) }
        }

        "the agent's system prompt, when given, comes before the selection instructions" {
            val system = slot<String>()
            coEvery {
                structuredLlmClient.call(
                    model = any(),
                    provider = any(),
                    systemPrompt = capture(system),
                    userMessage = any(),
                )
            } returns
                answer("""{"presetName": "Bench"}""")

            selector.choose(namespaceId, userId, description, presets, "Lexicon: a bench talent is unassigned.")

            system.captured shouldBe
                "Lexicon: a bench talent is unassigned.\n\n${FlowConfigurationPresetSelector.SYSTEM_PROMPT}"

            // blank: the selection instructions alone
            selector.choose(
                namespaceId = namespaceId,
                userId = userId,
                description = description,
                presets = presets,
                agentSystemPrompt = "  ",
            )
            system.captured shouldBe FlowConfigurationPresetSelector.SYSTEM_PROMPT
        }

        "a choice without rationale is still a choice" {
            llmReturns(answer("""{"presetName": "Stories"}"""))

            val outcome = selector.choose(namespaceId, userId, description, presets)

            outcome.preset shouldBe stories
            outcome.decision
                .shouldNotBeNull()
                .rationale
                .shouldBeNull()
        }

        "an answer in a code fence is used, with a warning" {
            llmReturns(
                StructuredLlmResult(
                    json = objectMapper.readTree("""{"presetName": "Bench"}"""),
                    rawText = "```json\n{\"presetName\": \"Bench\"}\n```",
                    usedCodeFence = true,
                ),
            )

            val outcome = selector.choose(namespaceId, userId, description, presets)

            outcome.preset shouldBe bench
            outcome.warnings.joinToString() shouldContain "code fence"
            outcome.unusableRawText.shouldBeNull()
        }

        "an unknown preset name yields no preset, warnings, and the raw text" {
            llmReturns(answer("""{"presetName": "Ghost"}"""))

            val outcome = selector.choose(namespaceId, userId, description, presets)

            outcome.preset.shouldBeNull()
            outcome.decision.shouldBeNull()
            outcome.warnings.first() shouldContain "The LLM chose preset 'Ghost', which is not among the presets [Stories, Bench]"
            outcome.warnings.last() shouldBe "Continuing without a preset."
            outcome.unusableRawText shouldBe """{"presetName": "Ghost"}"""
        }

        "a missing or non-text name yields no preset" {
            listOf("""{"rationale": "x"}""", """{"presetName": 3}""", """{"presetName": " "}""").forEach { text ->
                llmReturns(answer(text))

                val outcome = selector.choose(namespaceId, userId, description, presets)

                outcome.preset.shouldBeNull()
                outcome.warnings shouldContainExactly
                    listOf("The LLM did not choose a preset.", "Continuing without a preset.")
                outcome.unusableRawText shouldBe text
            }
        }

        "an answer with no JSON object, or no content, yields no preset" {
            llmReturns(StructuredLlmResult(json = null, rawText = "sorry"))
            val notJson = selector.choose(namespaceId, userId, description, presets)
            notJson.preset.shouldBeNull()
            notJson.warnings shouldContainExactly
                listOf("The LLM response could not be parsed as a JSON object.", "Continuing without a preset.")
            notJson.unusableRawText shouldBe "sorry"

            llmReturns(StructuredLlmResult(json = null, rawText = null))
            val nothing = selector.choose(namespaceId, userId, description, presets)
            nothing.preset.shouldBeNull()
            nothing.warnings shouldContainExactly listOf("The LLM returned no content.", "Continuing without a preset.")
            nothing.unusableRawText.shouldBeNull()
        }

        "no model configured is a 422 when the LLM is needed, and irrelevant for a single preset" {
            every { modelResolver.resolve(namespaceId, userId) } returns null

            shouldThrow<UnprocessableEntityException> { selector.choose(namespaceId, userId, description, presets) }
            selector.choose(namespaceId, userId, description, listOf(stories)).preset shouldBe stories
        }
    })
