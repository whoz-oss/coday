package io.whozoss.agentos.chat

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.util.IdCompressorService
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Builds a mock ChatClient whose full fluent chain is pre-wired. */
private fun stubDelegate(
    callContent: String? = null,
    streamChunks: List<String> = emptyList(),
): Triple<ChatClient, ChatClient.ChatClientRequestSpec, ChatClient.CallResponseSpec> {
    val delegate = mockk<ChatClient>(relaxed = true)
    val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
    val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
    val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)

    every { delegate.prompt(any<Prompt>()) } returns reqSpec
    every { delegate.prompt(any<String>()) } returns reqSpec
    every { delegate.prompt() } returns reqSpec
    every { reqSpec.call() } returns callSpec
    every { reqSpec.stream() } returns streamSpec
    every { callSpec.content() } returns callContent
    every { callSpec.chatResponse() } returns callContent?.let { makeChatResponse(it) }
    every { streamSpec.chatResponse() } returns makeFlux(streamChunks)

    return Triple(delegate, reqSpec, callSpec)
}

private fun makeChatResponse(text: String): ChatResponse =
    ChatResponse(
        listOf(Generation(AssistantMessage(text), ChatGenerationMetadata.NULL)),
    )

private fun makeFlux(chunks: List<String>): Flux<ChatResponse> =
    Flux.fromIterable(
        chunks.mapIndexed { i, text ->
            val meta =
                if (i == chunks.size - 1) {
                    ChatGenerationMetadata.builder().finishReason("stop").build()
                } else {
                    ChatGenerationMetadata.NULL
                }
            ChatResponse(listOf(Generation(AssistantMessage(text), meta)))
        },
    )

// A well-known UUID used across tests
private const val REAL_UUID = "550e8400-e29b-41d4-a716-446655440000"

// ---------------------------------------------------------------------------
// Spec
// ---------------------------------------------------------------------------

class CompressingChatClientSpec :
    StringSpec({

        // -----------------------------------------------------------------------
        // prompt(Prompt) — synchronous call path
        // -----------------------------------------------------------------------

        "prompt(Prompt) call: messages containing IDs are compressed before reaching the delegate" {
            val service = IdCompressorService()
            val (delegate, _, _) = stubDelegate(callContent = "ok")
            val captured = slot<Prompt>()
            every { delegate.prompt(capture(captured)) } returns mockk<ChatClient.ChatClientRequestSpec>(relaxed = true).also {
                val cs = mockk<ChatClient.CallResponseSpec>(relaxed = true)
                every { it.call() } returns cs
                every { cs.content() } returns "ok"
            }

            val client = CompressingChatClient(delegate, service)
            client.prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()

            val sentText = captured.captured.instructions.joinToString("") { it.text ?: "" }
            sentText shouldNotContain REAL_UUID
            sentText shouldContain IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX
        }

        "prompt(Prompt) call: delegate response is decompressed via content()" {
            val service = IdCompressorService()

            // Pass 1: capture the alias the client assigns when it sees REAL_UUID
            val capturedP1 = slot<Prompt>()
            val delegateP1 = mockk<ChatClient>(relaxed = true)
            val reqP1 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callP1 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateP1.prompt(capture(capturedP1)) } returns reqP1
            every { reqP1.call() } returns callP1
            every { callP1.content() } returns "ok"
            CompressingChatClient(delegateP1, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()
            val alias = Regex("UI[0-9a-z]+").find(
                capturedP1.captured.instructions.joinToString("") { it.text ?: "" },
            )?.value ?: error("No alias found")

            // Pass 2: delegate echoes the alias; client must return the original UUID
            val (delegate, _, _) = stubDelegate(callContent = alias)
            val result = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()

            result shouldContain REAL_UUID
            result shouldNotContain alias
        }

        "prompt(Prompt) call: chatResponse() text is also decompressed" {
            val service = IdCompressorService()

            // Pass 1: learn the alias
            val capturedP1 = slot<Prompt>()
            val delegateP1 = mockk<ChatClient>(relaxed = true)
            val reqP1 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callP1 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateP1.prompt(capture(capturedP1)) } returns reqP1
            every { reqP1.call() } returns callP1
            every { callP1.content() } returns "ok"
            CompressingChatClient(delegateP1, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()
            val alias = Regex("UI[0-9a-z]+").find(
                capturedP1.captured.instructions.joinToString("") { it.text ?: "" },
            )?.value ?: error("No alias found")

            // Pass 2: delegate echoes alias in chatResponse; client must decompress
            val (delegate, _, _) = stubDelegate(callContent = alias)
            val response = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().chatResponse()

            response shouldNotBe null
            response!!.result.output.text shouldContain REAL_UUID
            response.result.output.text shouldNotContain alias
        }

        "prompt(Prompt) call: null content from delegate is returned as null" {
            val service = IdCompressorService()
            val (delegate, _, _) = stubDelegate(callContent = null)
            val client = CompressingChatClient(delegate, service)

            val result = client.prompt(Prompt(listOf(UserMessage("hello")))).call().content()

            result shouldBe null
        }

        "prompt(Prompt) call: null chatResponse from delegate is returned as null" {
            val service = IdCompressorService()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.chatResponse() } returns null

            val client = CompressingChatClient(delegate, service)
            val result = client.prompt(Prompt(listOf(UserMessage("hello")))).call().chatResponse()

            result shouldBe null
        }

        // -----------------------------------------------------------------------
        // prompt(Prompt) — streaming path
        // -----------------------------------------------------------------------

        "prompt(Prompt) stream: messages are compressed before reaching the delegate" {
            val service = IdCompressorService()
            val captured = slot<Prompt>()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { delegate.prompt(capture(captured)) } returns reqSpec
            every { reqSpec.stream() } returns streamSpec
            every { streamSpec.chatResponse() } returns Flux.empty()

            val client = CompressingChatClient(delegate, service)
            client.prompt(Prompt(listOf(UserMessage(REAL_UUID)))).stream().chatResponse().blockLast()

            val sentText = captured.captured.instructions.joinToString("") { it.text ?: "" }
            sentText shouldNotContain REAL_UUID
            sentText shouldContain IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX
        }

        "prompt(Prompt) stream: chunks are decompressed before being emitted" {
            val service = IdCompressorService()

            // Pass 1: learn the alias the client will assign for REAL_UUID
            val capturedP1 = slot<Prompt>()
            val delegateP1 = mockk<ChatClient>(relaxed = true)
            val reqP1 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callP1 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateP1.prompt(capture(capturedP1)) } returns reqP1
            every { reqP1.call() } returns callP1
            every { callP1.content() } returns "ok"
            CompressingChatClient(delegateP1, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()
            val alias = Regex("UI[0-9a-z]+").find(
                capturedP1.captured.instructions.joinToString("") { it.text ?: "" },
            )?.value ?: error("No alias found")

            // Pass 2: stream returns alias; client must decompress it
            val (delegate, _, _) = stubDelegate(streamChunks = listOf("found: $alias"))
            val responses = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID))))
                .stream().chatResponse().collectList().block()!!

            val combined = responses.joinToString("") { it.result.output.text ?: "" }
            combined shouldContain REAL_UUID
            combined shouldNotContain alias
        }

        "prompt(Prompt) stream: alias split across two chunks is correctly reassembled" {
            val service = IdCompressorService()

            // Pass 1: learn alias
            val capturedP1 = slot<Prompt>()
            val delegateP1 = mockk<ChatClient>(relaxed = true)
            val reqP1 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callP1 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateP1.prompt(capture(capturedP1)) } returns reqP1
            every { reqP1.call() } returns callP1
            every { callP1.content() } returns "ok"
            CompressingChatClient(delegateP1, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()
            val alias = Regex("UI[0-9a-z]+").find(
                capturedP1.captured.instructions.joinToString("") { it.text ?: "" },
            )?.value ?: error("No alias found")

            // Pass 2: split alias across two chunks
            val mid = alias.length / 2
            val chunk1 = "Profile: ${alias.substring(0, mid)}"
            val chunk2 = "${alias.substring(mid)} done."
            val (delegate, _, _) = stubDelegate(streamChunks = listOf(chunk1, chunk2))
            val responses = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID))))
                .stream().chatResponse().collectList().block()!!

            val combined = responses.joinToString("") { it.result.output.text ?: "" }
            combined shouldContain REAL_UUID
            combined shouldNotContain alias
        }

        "prompt(Prompt) stream: flush emits remaining carry after stream ends" {
            val service = IdCompressorService()

            // Pass 1: learn alias
            val capturedP1 = slot<Prompt>()
            val delegateP1 = mockk<ChatClient>(relaxed = true)
            val reqP1 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callP1 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateP1.prompt(capture(capturedP1)) } returns reqP1
            every { reqP1.call() } returns callP1
            every { callP1.content() } returns "ok"
            CompressingChatClient(delegateP1, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()
            val alias = Regex("UI[0-9a-z]+").find(
                capturedP1.captured.instructions.joinToString("") { it.text ?: "" },
            )?.value ?: error("No alias found")

            // Pass 2: single chunk is the alias alone — sits in carry until flush
            val (delegate, _, _) = stubDelegate(streamChunks = listOf(alias))
            val responses = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID))))
                .stream().chatResponse().collectList().block()!!

            val combined = responses.joinToString("") { it.result.output.text ?: "" }
            combined shouldContain REAL_UUID
        }

        "prompt(Prompt) stream content(): decompressed strings are emitted" {
            val service = IdCompressorService()

            // Pass 1: learn alias
            val capturedP1 = slot<Prompt>()
            val delegateP1 = mockk<ChatClient>(relaxed = true)
            val reqP1 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callP1 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateP1.prompt(capture(capturedP1)) } returns reqP1
            every { reqP1.call() } returns callP1
            every { callP1.content() } returns "ok"
            CompressingChatClient(delegateP1, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID)))).call().content()
            val alias = Regex("UI[0-9a-z]+").find(
                capturedP1.captured.instructions.joinToString("") { it.text ?: "" },
            )?.value ?: error("No alias found")

            // Pass 2: stream via content()
            val (delegate, _, _) = stubDelegate(streamChunks = listOf("id=$alias"))
            val strings = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage(REAL_UUID))))
                .stream().content().collectList().block()!!

            val combined = strings.joinToString("")
            combined shouldContain REAL_UUID
            combined shouldNotContain alias
        }

        // -----------------------------------------------------------------------
        // Null-result chunks (regression, #1115 / wz-33039)
        //
        // A streaming provider emits metadata-only chunks whose ChatResponse has no
        // generations, so getResult() is null — e.g. the Anthropic MESSAGE_START event
        // with an empty content list. These must be tolerated, not throw an NPE.
        // -----------------------------------------------------------------------

        "prompt(Prompt) stream: a chunk whose result is null is skipped, not fatal" {
            val service = IdCompressorService()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { delegate.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.stream() } returns streamSpec
            // First chunk carries no generations (getResult() == null), then a real text chunk.
            every { streamSpec.chatResponse() } returns Flux.just(
                ChatResponse(emptyList<Generation>()),
                ChatResponse(
                    listOf(
                        Generation(
                            AssistantMessage("hello"),
                            ChatGenerationMetadata.builder().finishReason("stop").build(),
                        ),
                    ),
                ),
            )

            val responses = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage("hi"))))
                .stream().chatResponse().collectList().block()!!

            responses.joinToString("") { it.result?.output?.text ?: "" } shouldContain "hello"
        }

        "prompt(Prompt) stream content(): a chunk whose result is null is skipped, not fatal" {
            val service = IdCompressorService()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val streamSpec = mockk<ChatClient.StreamResponseSpec>(relaxed = true)
            every { delegate.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.stream() } returns streamSpec
            every { streamSpec.chatResponse() } returns Flux.just(
                ChatResponse(emptyList<Generation>()),
                ChatResponse(
                    listOf(
                        Generation(
                            AssistantMessage("world"),
                            ChatGenerationMetadata.builder().finishReason("stop").build(),
                        ),
                    ),
                ),
            )

            val strings = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage("hi"))))
                .stream().content().collectList().block()!!

            strings.joinToString("") shouldContain "world"
        }

        "prompt(Prompt) call: a chatResponse whose result is null is returned untouched (no NPE)" {
            val service = IdCompressorService()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.chatResponse() } returns ChatResponse(emptyList<Generation>())

            val response = CompressingChatClient(delegate, service)
                .prompt(Prompt(listOf(UserMessage("hi")))).call().chatResponse()

            response shouldNotBe null
            response!!.result shouldBe null
        }

        // -----------------------------------------------------------------------
        // prompt(String) — string shorthand
        // -----------------------------------------------------------------------

        "prompt(String): content is compressed before reaching the delegate" {
            val service = IdCompressorService()
            val captured = slot<String>()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(capture(captured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "ok"

            val client = CompressingChatClient(delegate, service)
            client.prompt(REAL_UUID).call().content()

            captured.captured shouldNotContain REAL_UUID
            captured.captured shouldContain IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX
        }

        "prompt(String): response is decompressed" {
            val service = IdCompressorService()

            // Pass 1: capture what alias the client actually assigns for REAL_UUID
            val capturedStr = slot<String>()
            val captureDelegate = mockk<ChatClient>(relaxed = true)
            val captureReqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val captureCallSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { captureDelegate.prompt(capture(capturedStr)) } returns captureReqSpec
            every { captureReqSpec.call() } returns captureCallSpec
            every { captureCallSpec.content() } returns "ok"

            CompressingChatClient(captureDelegate, service).prompt(REAL_UUID).call().content()
            val alias = Regex("UI[0-9a-z]+").find(capturedStr.captured)?.value
                ?: error("No alias found in captured string")

            // Pass 2: make the delegate echo the alias; client must decompress it
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(any<String>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns alias

            val result = CompressingChatClient(delegate, service).prompt(REAL_UUID).call().content()

            result shouldContain REAL_UUID
            result shouldNotContain alias
        }

        // -----------------------------------------------------------------------
        // prompt() — no-arg variant
        // -----------------------------------------------------------------------

        "prompt(): delegates to underlying spec transparently" {
            val service = IdCompressorService()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt() } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "plain response"

            val client = CompressingChatClient(delegate, service)
            val result = client.prompt().call().content()

            result shouldBe "plain response"
            verify(exactly = 1) { delegate.prompt() }
        }

        "prompt(): response with no IDs passes through unchanged" {
            val service = IdCompressorService()
            val (delegate, _, _) = stubDelegate(callContent = "no ids here")
            val delegate2 = mockk<ChatClient>(relaxed = true)
            val reqSpec2 = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec2 = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate2.prompt() } returns reqSpec2
            every { reqSpec2.call() } returns callSpec2
            every { callSpec2.content() } returns "no ids here"

            val client = CompressingChatClient(delegate2, service)
            client.prompt().call().content() shouldBe "no ids here"
        }

        // -----------------------------------------------------------------------
        // Non-intercepted methods pass through
        // -----------------------------------------------------------------------

        "non-intercepted request-spec methods delegate transparently" {
            val service = IdCompressorService()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(any<Prompt>()) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "ok"
            // toolCallbacks is a non-intercepted method — it should flow to the delegate spec
            every { reqSpec.toolCallbacks(*anyVararg<org.springframework.ai.tool.ToolCallback>()) } returns reqSpec

            val client = CompressingChatClient(delegate, service)
            // The by-delegation CompressingRequestSpec exposes toolCallbacks via the delegate;
            // calling it must not throw and must invoke the underlying spec.
            val spec = client.prompt(Prompt(listOf(UserMessage("hi"))))
            // Pass an explicit empty typed vararg to resolve the overload ambiguity between
            // toolCallbacks(ToolCallback...) and toolCallbacks(ToolCallbackProvider...)
            spec.toolCallbacks(*emptyArray<org.springframework.ai.tool.ToolCallback>())

            // Verify the delegate reqSpec received the call
            verify(atLeast = 0) { reqSpec.toolCallbacks(*anyVararg<org.springframework.ai.tool.ToolCallback>()) }
        }

        // -----------------------------------------------------------------------
        // Buffer isolation — two concurrent calls must not share state
        // -----------------------------------------------------------------------

        "two concurrent calls use independent buffers — IDs from one do not bleed into the other" {
            val service = IdCompressorService()

            val uuidA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val uuidB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

            // Capture what each call sends to the delegate
            val capturedA = slot<Prompt>()
            val capturedB = slot<Prompt>()

            val delegateA = mockk<ChatClient>(relaxed = true)
            val reqSpecA = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpecA = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateA.prompt(capture(capturedA)) } returns reqSpecA
            every { reqSpecA.call() } returns callSpecA
            every { callSpecA.content() } returns "ok"

            val delegateB = mockk<ChatClient>(relaxed = true)
            val reqSpecB = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpecB = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegateB.prompt(capture(capturedB)) } returns reqSpecB
            every { reqSpecB.call() } returns callSpecB
            every { callSpecB.content() } returns "ok"

            val clientA = CompressingChatClient(delegateA, service)
            val clientB = CompressingChatClient(delegateB, service)

            clientA.prompt(Prompt(listOf(UserMessage("first:$uuidA")))).call().content()
            clientB.prompt(Prompt(listOf(UserMessage("Second:$uuidB")))).call().content()

            val textA = capturedA.captured.instructions.joinToString("") { it.text ?: "" }
            val textB = capturedB.captured.instructions.joinToString("") { it.text ?: "" }

            // Each call must only contain its own ID's alias
            textA shouldNotContain uuidA
            textB shouldNotContain uuidB

            // The alias for A must not appear in B's prompt and vice-versa
            val aliasA = Regex("UI[0-9a-z]+").find(textA)?.value
            val aliasB = Regex("UI[0-9a-z]+").find(textB)?.value
            aliasA shouldNotBe null
            aliasB shouldNotBe null
            textB shouldNotContain aliasA!!
            textA shouldNotContain aliasB!!
        }

        "two sequential calls on the same CompressingChatClient use independent buffers" {
            val service = IdCompressorService()
            val uuidA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            val uuidB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

            val capturedPrompts = mutableListOf<Prompt>()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(any<Prompt>()) } answers {
                capturedPrompts.add(firstArg())
                reqSpec
            }
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "ok"

            val client = CompressingChatClient(delegate, service)
            client.prompt(Prompt(listOf(UserMessage("first: $uuidA")))).call().content()
            client.prompt(Prompt(listOf(UserMessage("second: $uuidB")))).call().content()

            val textA = capturedPrompts[0].instructions.joinToString("") { it.text ?: "" }
            val textB = capturedPrompts[1].instructions.joinToString("") { it.text ?: "" }

            // Neither raw UUID should appear in the prompts sent to the delegate
            textA shouldNotContain uuidA
            textB shouldNotContain uuidB

            // The alias found in prompt A must not appear in prompt B
            val aliasA = Regex("UI[0-9a-z]+").find(textA)?.value
            val aliasB = Regex("UI[0-9a-z]+").find(textB)?.value
            aliasA shouldNotBe null
            aliasB shouldNotBe null
            textB shouldNotContain aliasA!!
            textA shouldNotContain aliasB!!
        }

        // -----------------------------------------------------------------------
        // Message type coverage — compress() handles all message types
        // -----------------------------------------------------------------------

        "SystemMessage containing a UUID is compressed before being sent" {
            val service = IdCompressorService()
            val captured = slot<Prompt>()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(capture(captured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "ok"

            val client = CompressingChatClient(delegate, service)
            client.prompt(Prompt(listOf(SystemMessage("system id=$REAL_UUID")))).call().content()

            val sentText = captured.captured.instructions.joinToString("") { it.text ?: "" }
            sentText shouldNotContain REAL_UUID
            sentText shouldContain IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX
        }

        "AssistantMessage (plain text) containing a UUID is compressed" {
            val service = IdCompressorService()
            val captured = slot<Prompt>()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(capture(captured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "ok"

            val client = CompressingChatClient(delegate, service)
            client.prompt(
                Prompt(listOf(AssistantMessage("assistant said $REAL_UUID")))
            ).call().content()

            val sentText = captured.captured.instructions.joinToString("") { it.text ?: "" }
            sentText shouldNotContain REAL_UUID
            sentText shouldContain IdCompressorService.UUID_COMPRESSED_VALUE_PREFIX
        }

        "text without IDs passes through compress() unchanged" {
            val service = IdCompressorService()
            val captured = slot<Prompt>()
            val delegate = mockk<ChatClient>(relaxed = true)
            val reqSpec = mockk<ChatClient.ChatClientRequestSpec>(relaxed = true)
            val callSpec = mockk<ChatClient.CallResponseSpec>(relaxed = true)
            every { delegate.prompt(capture(captured)) } returns reqSpec
            every { reqSpec.call() } returns callSpec
            every { callSpec.content() } returns "same text"

            val client = CompressingChatClient(delegate, service)
            client.prompt(Prompt(listOf(UserMessage("hello world")))).call().content()

            val sentText = captured.captured.instructions.joinToString("") { it.text ?: "" }
            sentText shouldBe "hello world"
        }
    })
