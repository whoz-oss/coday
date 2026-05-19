package io.whozoss.agentos.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt

/**
 * Unit specs for [ConfirmationManager].
 *
 * The collaborator under test wraps a Spring AI [ChatClient] — we mock the full chain
 * `prompt(Prompt) → call() → content(): String` so we can inject controlled `<decision>`
 * payloads and assert the decoded outcome.
 */
class ConfirmationManagerSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()
        val manager = ConfirmationManager(mapper)

        fun stubChatClient(rawResponse: String): ChatClient {
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns rawResponse
            return chatClient
        }

        // ─── shouldConfirm ──────────────────────────────────────────────────────────────────

        "shouldConfirm returns false when the LLM says the user already authorised (yes)" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.shouldConfirm(
                chatClient = chatClient,
                history = emptyList(),
                actionLabel = "Delete file /tmp/x",
                proposedData = mapOf("path" to "/tmp/x"),
            ) shouldBe false
        }

        "shouldConfirm returns true when the LLM says explicit confirmation is needed (no)" {
            val chatClient = stubChatClient("<decision>no</decision>")
            manager.shouldConfirm(
                chatClient = chatClient,
                history = emptyList(),
                actionLabel = "Delete file /tmp/x",
                proposedData = mapOf("path" to "/tmp/x"),
            ) shouldBe true
        }

        "shouldConfirm fail-safes to true on any LLM exception" {
            val chatClient = mockk<ChatClient>()
            every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("LLM down")
            manager.shouldConfirm(
                chatClient = chatClient,
                history = emptyList(),
                actionLabel = "Delete file /tmp/x",
                proposedData = mapOf("path" to "/tmp/x"),
            ) shouldBe true
        }

        "shouldConfirm accepts an optional originalData for delta-aware Update tools (back Copilot parity)" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.shouldConfirm(
                chatClient = chatClient,
                history = listOf(UserMessage("apply the rename")),
                actionLabel = "Rename profile",
                proposedData = mapOf("name" to "new"),
                originalData = mapOf("name" to "old"),
            ) shouldBe false
        }

        // ─── analyzeConfirmation ────────────────────────────────────────────────────────────

        "analyzeConfirmation returns true on explicit yes" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                history = listOf(UserMessage("oui supprime")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                specificInstructions = "",
            ) shouldBe true
        }

        "analyzeConfirmation returns false on explicit no" {
            val chatClient = stubChatClient("<decision>no</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                history = listOf(UserMessage("annule")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                specificInstructions = "",
            ) shouldBe false
        }

        "analyzeConfirmation throws AmbiguousConfirmationException on an undecodable LLM reply" {
            val chatClient = stubChatClient("the model wandered off-topic, no decision tag here")
            shouldThrow<AmbiguousConfirmationException> {
                manager.analyzeConfirmation(
                    chatClient = chatClient,
                    history = listOf(UserMessage("euh quoi ?")),
                    pendingPayload = mapOf("path" to "/tmp/x"),
                    specificInstructions = "",
                )
            }
        }

        "analyzeConfirmation wraps underlying LLM exceptions into AmbiguousConfirmationException" {
            val chatClient = mockk<ChatClient>()
            every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("rate limited")
            shouldThrow<AmbiguousConfirmationException> {
                manager.analyzeConfirmation(
                    chatClient = chatClient,
                    history = emptyList(),
                    pendingPayload = mapOf("path" to "/tmp/x"),
                    specificInstructions = "",
                )
            }
        }

        "analyzeConfirmation accepts tool-supplied specific instructions" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                history = listOf(UserMessage("ok delete it")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                specificInstructions = "Be strict: a bare 'ok' is acceptable only if the previous turn described the deletion.",
            ) shouldBe true
        }

        // ─── decision tag tolerance ─────────────────────────────────────────────────────────

        "analyzeConfirmation tolerates whitespace around the decision tag content" {
            val chatClient = stubChatClient("Looking at the history… <decision>  yes  </decision> done.")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                history = emptyList(),
                pendingPayload = mapOf("path" to "/tmp/x"),
                specificInstructions = "",
            ) shouldBe true
        }

        // ─── formulateQuestion ──────────────────────────────────────────────────────────────

        "formulateQuestion returns the LLM-extracted question when the tag is present" {
            val chatClient =
                stubChatClient("<question>Je vais supprimer scratch.txt, tu confirmes ?</question>")
            manager.formulateQuestion(
                chatClient = chatClient,
                history = listOf(UserMessage("supprime scratch.txt")),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
            ) shouldBe "Je vais supprimer scratch.txt, tu confirmes ?"
        }

        "formulateQuestion falls back to the deterministic label on any LLM exception" {
            val chatClient = mockk<ChatClient>()
            every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("LLM down")
            manager.formulateQuestion(
                chatClient = chatClient,
                history = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
            ) shouldBe "Delete file scratch.txt"
        }

        "formulateQuestion falls back to the label when the LLM reply is blank inside the tag" {
            val chatClient = stubChatClient("<question>   </question>")
            manager.formulateQuestion(
                chatClient = chatClient,
                history = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
            ) shouldBe "Delete file scratch.txt"
        }
    })
