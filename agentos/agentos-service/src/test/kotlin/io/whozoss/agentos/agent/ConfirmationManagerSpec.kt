package io.whozoss.agentos.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.util.UUID

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

        /**
         * Build a minimal [AgentAdvancedContext] for [ConfirmationManager.formulateQuestion] tests.
         * The context wraps the given [chatClient] with empty tools, no instructions, and
         * a random agentId — just enough for [AgentAdvancedContext.buildMessages] to work.
         */
        fun buildContext(chatClient: ChatClient): AgentAdvancedContext =
            AgentAdvancedContext(
                chatClient = chatClient,
                tools = emptyList(),
                instructions = null,
                agentId = UUID.randomUUID(),
                confirmationManager = manager,
            )

        // ─── shouldConfirm ──────────────────────────────────────────────────────────────────

        "shouldConfirm returns false when the LLM says the user already authorised (yes)" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.shouldConfirm(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                actionLabel = "Delete file /tmp/x",
                proposedData = mapOf("path" to "/tmp/x"),
            ) shouldBe false
        }

        "shouldConfirm returns true when the LLM says explicit confirmation is needed (no)" {
            val chatClient = stubChatClient("<decision>no</decision>")
            manager.shouldConfirm(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                actionLabel = "Delete file /tmp/x",
                proposedData = mapOf("path" to "/tmp/x"),
            ) shouldBe true
        }

        "shouldConfirm fail-safes to true on any LLM exception" {
            val chatClient = mockk<ChatClient>()
            every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("LLM down")
            manager.shouldConfirm(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                actionLabel = "Delete file /tmp/x",
                proposedData = mapOf("path" to "/tmp/x"),
            ) shouldBe true
        }

        "shouldConfirm accepts an optional originalData for delta-aware Update tools (back Copilot parity)" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.shouldConfirm(
                chatClient = chatClient,
                firstLevelHistory = listOf(UserMessage("apply the rename")),
                actionLabel = "Rename profile",
                proposedData = mapOf("name" to "new"),
                originalData = mapOf("name" to "old"),
            ) shouldBe false
        }

        // Le tool fournit des instructions custom via getConfirmationInstructions() ;
        // ConfirmationManager les injecte comme une section nommée "Tool-specific
        // confirmation guidance:" dans le prompt — même pattern que les autres sections
        // contextuelles (Original object, Current Situation, Proposed changes…). La phrase
        // "additional context, not overriding" désamorce un conflit avec les règles génériques.
        "shouldConfirm injects toolInstructions as a labelled section" {
            val promptCaptured = slot<Prompt>()
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(capture(promptCaptured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "<decision>no</decision>"

            manager.shouldConfirm(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                actionLabel = "Update profile",
                proposedData = mapOf("name" to "new"),
                toolInstructions = "Hesitant phrasing like 'pourquoi pas' is not consent.",
            ) shouldBe true

            val promptText = promptCaptured.captured.instructions.joinToString("\n") { it.text ?: "" }
            promptText shouldContain "Tool-specific confirmation guidance:"
            promptText shouldContain "Hesitant phrasing like 'pourquoi pas' is not consent."
        }

        "shouldConfirm omits the tool guidance section when toolInstructions is blank" {
            val promptCaptured = slot<Prompt>()
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(capture(promptCaptured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "<decision>yes</decision>"

            manager.shouldConfirm(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                actionLabel = "Update profile",
                proposedData = mapOf("name" to "new"),
                toolInstructions = "",
            ) shouldBe false

            val promptText = promptCaptured.captured.instructions.joinToString("\n") { it.text ?: "" }
            promptText shouldNotContain "Tool-specific confirmation guidance"
        }

        // ─── analyzeConfirmation ────────────────────────────────────────────────────────────

        "analyzeConfirmation returns CONFIRMED on explicit yes" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = listOf(UserMessage("oui supprime")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "",
            ) shouldBe ConfirmationDecision.CONFIRMED
        }

        "analyzeConfirmation returns REJECTED on explicit no" {
            val chatClient = stubChatClient("<decision>no</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = listOf(UserMessage("annule")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "",
            ) shouldBe ConfirmationDecision.REJECTED
        }

        "analyzeConfirmation returns AMBIGUOUS on an undecodable LLM reply" {
            val chatClient = stubChatClient("the model wandered off-topic, no decision tag here")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = listOf(UserMessage("euh quoi ?")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "",
            ) shouldBe ConfirmationDecision.AMBIGUOUS
        }

        "analyzeConfirmation returns AMBIGUOUS when the LLM call throws" {
            val chatClient = mockk<ChatClient>()
            every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("rate limited")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "",
            ) shouldBe ConfirmationDecision.AMBIGUOUS
        }

        "analyzeConfirmation accepts tool-supplied specific instructions" {
            val chatClient = stubChatClient("<decision>yes</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = listOf(UserMessage("ok delete it")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "Be strict: a bare 'ok' is acceptable only if the previous turn described the deletion.",
            ) shouldBe ConfirmationDecision.CONFIRMED
        }

        "analyzeConfirmation returns AMBIGUOUS when the LLM explicitly says unclear" {
            val chatClient = stubChatClient("<decision>unclear</decision>")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = listOf(UserMessage("idiomatic ambiguous reply")),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "",
            ) shouldBe ConfirmationDecision.AMBIGUOUS
        }

        // ─── decision tag tolerance ─────────────────────────────────────────────────────────

        "analyzeConfirmation tolerates whitespace around the decision tag content" {
            val chatClient = stubChatClient("Looking at the history… <decision>  yes  </decision> done.")
            manager.analyzeConfirmation(
                chatClient = chatClient,
                firstLevelHistory = emptyList(),
                pendingPayload = mapOf("path" to "/tmp/x"),
                toolInstructions = "",
            ) shouldBe ConfirmationDecision.CONFIRMED
        }

        // ─── formulateQuestion ──────────────────────────────────────────────────────────────

        "formulateQuestion returns the LLM-extracted question when the tag is present" {
            val chatClient =
                stubChatClient("<question>Je vais supprimer scratch.txt, tu confirmes ?</question>")
            val context = buildContext(chatClient)
            manager.formulateQuestion(
                context = context,
                chatClient = chatClient,
                accumulatedEvents = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
            ) shouldBe "Je vais supprimer scratch.txt, tu confirmes ?"
        }

        "formulateQuestion falls back to the deterministic label on any LLM exception" {
            val chatClient = mockk<ChatClient>()
            every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("LLM down")
            val context = buildContext(chatClient)
            manager.formulateQuestion(
                context = context,
                chatClient = chatClient,
                accumulatedEvents = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
            ) shouldBe "Delete file scratch.txt"
        }

        "formulateQuestion falls back to the label when the LLM reply is blank inside the tag" {
            val chatClient = stubChatClient("<question>   </question>")
            val context = buildContext(chatClient)
            manager.formulateQuestion(
                context = context,
                chatClient = chatClient,
                accumulatedEvents = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
            ) shouldBe "Delete file scratch.txt"
        }

        "formulateQuestion injects guidelines into the prompt when provided" {
            val promptCaptured = slot<Prompt>()
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(capture(promptCaptured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "<question>Confirmes-tu la suppression ?</question>"

            val context = buildContext(chatClient)
            manager.formulateQuestion(
                context = context,
                chatClient = chatClient,
                accumulatedEvents = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
                guidelines = "Respond in the same language the user is writing in.",
            )

            val promptText = promptCaptured.captured.instructions.joinToString("\n") { it.text ?: "" }
            promptText shouldContain "Respond in the same language the user is writing in."
        }

        "formulateQuestion does not inject guidelines into the prompt when null" {
            val promptCaptured = slot<Prompt>()
            val chatClient = mockk<ChatClient>()
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callSpec = mockk<ChatClient.CallResponseSpec>()
            every { chatClient.prompt(capture(promptCaptured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "<question>Confirmes-tu la suppression ?</question>"

            val context = buildContext(chatClient)
            manager.formulateQuestion(
                context = context,
                chatClient = chatClient,
                accumulatedEvents = emptyList(),
                fallbackLabel = "Delete file scratch.txt",
                pendingData = mapOf("path" to "scratch.txt"),
                guidelines = null,
            )

            val promptText = promptCaptured.captured.instructions.joinToString("\n") { it.text ?: "" }
            promptText shouldNotContain "Do not discriminate"
            promptText shouldNotContain "Do not reference technical IDs"
        }

        "formulateQuestion strips orphan question tags when the LLM omits the opening one" {
            // LLM only emits the closing tag (regex fallback path); sanitizeQuestion must
            // strip the orphan so it does not leak into the IN-CHANNEL MessageEvent.
            val chatClient = stubChatClient("Confirmes-tu la suppression du fichier x ?</question>")
            val context = buildContext(chatClient)
            val out =
                manager.formulateQuestion(
                    context = context,
                    chatClient = chatClient,
                    accumulatedEvents = emptyList(),
                    fallbackLabel = "Delete file x",
                    pendingData = mapOf("path" to "x"),
                )
            out shouldNotContain "</question>"
            out shouldNotContain "<question>"
        }
    })
