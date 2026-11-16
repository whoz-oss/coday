package io.whozoss.agentos.generation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.chat.ChatClientProvider
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

/**
 * Unit tests for [StructuredLlmClient]: the call (prompt content, client creation) and the tolerant parse of the
 * answer (direct, code-fence fallback, nothing usable, nothing at all).
 */
class StructuredLlmClientUnitSpec : StringSpec({

    val objectMapper = ObjectMapper().registerKotlinModule()
    val nsId = UUID.randomUUID()
    val model =
        AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = UUID.randomUUID(),
            namespaceId = nsId,
            apiModelName = "claude-3-5-haiku",
        )
    val provider =
        AiProvider(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = nsId,
            name = "anthropic",
            apiType = AiApiType.Anthropic,
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
        )

    val sentPrompt = slot<Prompt>()

    fun clientAnswering(llmResponse: String?): Pair<StructuredLlmClient, ChatClientProvider> {
        val chatClient = mockk<ChatClient>(relaxed = true)
        every { chatClient.prompt(capture(sentPrompt)).call().content() } returns llmResponse
        val chatClientProvider = mockk<ChatClientProvider>()
        every { chatClientProvider.getChatClient(any(), any(), null, null) } returns chatClient
        return StructuredLlmClient(chatClientProvider, objectMapper) to chatClientProvider
    }

    fun call(llmResponse: String?) = clientAnswering(llmResponse).first.call(model, provider, "system", "user")

    "sends the system prompt then the user message, on a client of the given model and provider with no case and no accumulator" {
        val (client, chatClientProvider) = clientAnswering("{}")

        client.call(model, provider, "the system prompt", "the user message")

        verify(exactly = 1) { chatClientProvider.getChatClient(model, provider, null, null) }
        val messages = sentPrompt.captured.instructions
        messages.size shouldBe 2
        (messages[0] is SystemMessage) shouldBe true
        messages[0].text shouldBe "the system prompt"
        (messages[1] is UserMessage) shouldBe true
        messages[1].text shouldBe "the user message"
    }

    "parses a direct JSON object, whitespace around it included, without the fence flag" {
        forAll(
            row("""{"a":1}"""),
            row("  \n {\"a\":1} \n  "),
        ) { answer ->
            val result = call(answer)

            result.json.shouldNotBeNull()
            result.json["a"].asInt() shouldBe 1
            result.rawText shouldBe answer
            result.usedCodeFence shouldBe false
            result.returnedNothing shouldBe false
        }
    }

    "falls back to the first code fence, json-tagged or bare, and says so" {
        forAll(
            row("Here you go:\n```json\n{\"a\":1}\n```\nHope it helps"),
            row("```\n{\"a\":1}\n```"),
            // only the first fence counts
            row("```json\n{\"a\":1}\n```\nand then\n```json\n{\"a\":2}\n```"),
        ) { answer ->
            val result = call(answer)

            result.json.shouldNotBeNull()
            result.json["a"].asInt() shouldBe 1
            result.rawText shouldBe answer
            result.usedCodeFence shouldBe true
        }
    }

    "keeps the raw text and no json when the answer holds no JSON object" {
        forAll(
            row("I cannot do that."),
            // valid JSON, but not an object
            row("[1, 2]"),
            row("42"),
            // a fence that is never closed, or that holds no object
            row("```json\n{\"a\":1}"),
            row("```\nnot json\n```"),
        ) { answer ->
            val result = call(answer)

            result.json.shouldBeNull()
            result.rawText shouldBe answer
            result.usedCodeFence shouldBe false
            result.returnedNothing shouldBe false
        }
    }

    "reports that the LLM returned nothing, for null and blank content" {
        forAll(
            row<String?>(null),
            row<String?>(""),
            row<String?>("  \n "),
        ) { answer ->
            val result = call(answer)

            result.json.shouldBeNull()
            result.rawText.shouldBeNull()
            result.returnedNothing shouldBe true
        }
    }

    "lets an exception of the LLM call propagate" {
        val chatClient = mockk<ChatClient>(relaxed = true)
        every { chatClient.prompt(any<Prompt>()).call().content() } throws IllegalStateException("provider down")
        val chatClientProvider = mockk<ChatClientProvider>()
        every { chatClientProvider.getChatClient(any(), any(), null, null) } returns chatClient

        shouldThrow<IllegalStateException> {
            StructuredLlmClient(chatClientProvider, objectMapper).call(model, provider, "system", "user")
        }.message shouldBe "provider down"
    }
})
