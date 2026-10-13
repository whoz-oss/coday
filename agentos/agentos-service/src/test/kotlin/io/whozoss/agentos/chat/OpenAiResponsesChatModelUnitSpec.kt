package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.openai.client.OpenAIClient
import com.openai.core.jsonMapper
import com.openai.core.http.Headers
import com.openai.core.http.StreamResponse
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.SseException
import com.openai.models.ErrorObject
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseStreamEvent
import com.openai.services.blocking.ResponseService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.ModelPricing
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.util.UUID
import java.util.stream.Stream

class OpenAiResponsesChatModelUnitSpec :
    StringSpec({

        val endpoint = "https://api.openai.com/v1/responses"
        val jsonMapper = jsonMapper()

        fun response(name: String): Response =
            jsonMapper.readValue(
                requireNotNull(javaClass.classLoader.getResource("openai-responses/$name")) { name }.readText(),
                Response::class.java,
            )

        fun body(params: ResponseCreateParams): JsonNode = jsonMapper.valueToTree(params._body())

        fun events(name: String): List<ResponseStreamEvent> =
            requireNotNull(javaClass.classLoader.getResource("openai-responses/$name")) { name }
                .readText()
                .lines()
                .filter { it.isNotBlank() }
                .map { jsonMapper.readValue(it, ResponseStreamEvent::class.java) }

        /**
         * Server-sent events replayed from a fixture, optionally followed by [failure] as the SDK
         * raises it mid-stream; records whether the HTTP response was closed.
         */
        class FixtureStream(
            private val events: List<ResponseStreamEvent>,
            private val failure: RuntimeException? = null,
        ) : StreamResponse<ResponseStreamEvent> {
            @Volatile var closed = false

            override fun stream(): Stream<ResponseStreamEvent> =
                if (failure == null) {
                    events.stream()
                } else {
                    Stream.concat(events.stream(), Stream.generate<ResponseStreamEvent> { throw failure }.limit(1))
                }

            override fun close() {
                closed = true
            }
        }

        fun streamingService(vararg streams: FixtureStream): Pair<ResponseService, MutableList<ResponseCreateParams>> {
            val sent = mutableListOf<ResponseCreateParams>()
            val service = mockk<ResponseService>()
            every { service.createStreaming(capture(sent)) } returnsMany streams.toList()
            return service to sent
        }

        class RecordingTimeTool : ToolCallback {
            val inputs = mutableListOf<String>()

            override fun getToolDefinition(): ToolDefinition =
                ToolDefinition
                    .builder()
                    .name("get_time")
                    .description("Current time")
                    .inputSchema("""{"type":"object","properties":{}}""")
                    .build()

            override fun call(toolInput: String): String {
                inputs += toolInput
                return "13:37"
            }
        }

        fun modelWith(service: ResponseService): OpenAiResponsesChatModel {
            val client = mockk<OpenAIClient> { every { responses() } returns service }
            return OpenAiResponsesChatModel(
                client = client,
                defaultOptions = OpenAiChatOptions.builder().model("gpt-6-sol").build(),
                endpoint = endpoint,
            )
        }

        fun serviceAnswering(vararg responses: Response): Pair<ResponseService, MutableList<ResponseCreateParams>> {
            val sent = mutableListOf<ResponseCreateParams>()
            val service = mockk<ResponseService>()
            every { service.create(capture(sent)) } returnsMany responses.toList()
            return service to sent
        }

        "call sends the default model with store disabled and maps the text answer" {
            val (service, sent) = serviceAnswering(response("response-text.json"))

            val chatResponse = modelWith(service).call(Prompt(UserMessage("Say hello")))

            chatResponse.result.output.text shouldBe "Hello."
            sent shouldHaveSize 1
            body(sent[0])["model"].asText() shouldBe "gpt-6-sol"
            body(sent[0])["store"].asBoolean() shouldBe false
        }

        "tool calls run in the model and their output goes back when internal tool execution is on" {
            val tool = RecordingTimeTool()
            val (service, sent) = serviceAnswering(response("response-function-call.json"), response("response-text.json"))

            val chatResponse =
                modelWith(service).call(
                    Prompt(listOf(UserMessage("What time is it?")), OpenAiChatOptions.builder().toolCallbacks(listOf(tool)).build()),
                )

            chatResponse.result.output.text shouldBe "Hello."
            tool.inputs shouldContainExactly listOf("{}")
            sent shouldHaveSize 2
            body(sent[0])["tools"][0]["name"].asText() shouldBe "get_time"
            val replayed = body(sent[1])["input"]
            replayed.map { it["type"]?.asText() ?: "message" } shouldContainExactly
                listOf("message", "function_call", "function_call_output")
            replayed[2]["call_id"].asText() shouldBe "call_HoZNaI7y22Z0j5VoytjTJIuP"
            replayed[2]["output"].asText() shouldBe "13:37"
        }

        "tool calls are returned to the caller when internal tool execution is off" {
            val tool = RecordingTimeTool()
            val (service, sent) = serviceAnswering(response("response-function-call.json"))
            val options =
                OpenAiChatOptions
                    .builder()
                    .toolCallbacks(listOf(tool))
                    .internalToolExecutionEnabled(false)
                    .build()

            val chatResponse = modelWith(service).call(Prompt(listOf(UserMessage("What time is it?")), options))

            chatResponse.result.output.toolCalls.map { it.name() } shouldContainExactly listOf("get_time")
            tool.inputs shouldBe emptyList()
            sent shouldHaveSize 1
        }

        "a 4xx from OpenAI names the status and endpoint only, without the error body" {
            val service = mockk<ResponseService>()
            every { service.create(any<ResponseCreateParams>()) } throws
                BadRequestException
                    .builder()
                    .headers(Headers.builder().build())
                    .error(
                        ErrorObject
                            .builder()
                            .code(null)
                            .message("echoed user content")
                            .param("tools")
                            .type("invalid_request_error")
                            .build(),
                    ).build()

            val error = shouldThrow<NonTransientAiException> { modelWith(service).call(Prompt(UserMessage("Hi"))) }

            error.message shouldBe "400 BAD_REQUEST from POST $endpoint"
            error.message shouldNotContain "echoed user content"
            error.cause.shouldBeNull()
        }

        "a 5xx from OpenAI is transient" {
            val service = mockk<ResponseService>()
            every { service.create(any<ResponseCreateParams>()) } throws
                InternalServerException
                    .builder()
                    .statusCode(503)
                    .headers(Headers.builder().build())
                    .build()

            val error = shouldThrow<TransientAiException> { modelWith(service).call(Prompt(UserMessage("Hi"))) }

            error.message shouldBe "503 SERVICE_UNAVAILABLE from POST $endpoint"
        }

        "stream emits text deltas, then a final chunk with the usage, and closes the HTTP response" {
            val stream = FixtureStream(events("stream-text.jsonl"))
            val (service, _) = streamingService(stream)

            val chunks = modelWith(service).stream(Prompt(UserMessage("Count to 5"))).collectList().block()!!

            chunks.dropLast(1).joinToString("") { it.result.output.text.orEmpty() } shouldBe "1, 2, 3, 4, 5"
            chunks.last().result.output.text shouldBe ""
            chunks.last().metadata.usage.totalTokens shouldBe 34
            stream.closed shouldBe true
        }

        "streamed tool calls run in the model and the follow-up answer is streamed in turn" {
            val tool = RecordingTimeTool()
            val toolRound = FixtureStream(events("stream-function-call.jsonl"))
            val answerRound = FixtureStream(events("stream-text.jsonl"))
            val (service, sent) = streamingService(toolRound, answerRound)
            val options = OpenAiChatOptions.builder().toolCallbacks(listOf(tool)).build()

            val chunks = modelWith(service).stream(Prompt(listOf(UserMessage("What time is it?")), options)).collectList().block()!!

            chunks.joinToString("") { it.result.output.text.orEmpty() } shouldBe "1, 2, 3, 4, 5"
            tool.inputs shouldContainExactly listOf("{}")
            sent shouldHaveSize 2
            body(sent[1])["input"].map { it["type"]?.asText() ?: "message" } shouldContainExactly
                listOf("message", "function_call", "function_call_output")
            toolRound.closed shouldBe true
            answerRound.closed shouldBe true
        }

        "with usage tracking, ChatClient streaming runs the tools in UsageTrackingChatModel and records each request" {
            val tool = RecordingTimeTool()
            val (service, sent) =
                streamingService(FixtureStream(events("stream-function-call.jsonl")), FixtureStream(events("stream-text.jsonl")))
            val accumulator = UsageAccumulator()
            val aiModel =
                AiModel(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    aiProviderId = UUID.randomUUID(),
                    apiModelName = "gpt-6-sol",
                    pricing = ModelPricing(inputMTokens = 2.0, outputMTokens = 10.0, cacheRead = 0.2),
                )
            val tracked = UsageTrackingChatModel(modelWith(service), accumulator, AiApiType.OpenAIResponses, aiModel)

            val text =
                ChatClient
                    .builder(tracked)
                    .build()
                    .prompt("What time is it?")
                    .toolCallbacks(tool)
                    .stream()
                    .content()
                    .collectList()
                    .block()!!
                    .joinToString("")

            text shouldBe "1, 2, 3, 4, 5"
            tool.inputs shouldContainExactly listOf("{}")
            sent shouldHaveSize 2
            val snapshot = accumulator.snapshot()
            snapshot.calls shouldBe 2
            snapshot.unknownCalls shouldBe 0
            snapshot.usage.inputTokens shouldBe 42L + 17L
            snapshot.usage.outputTokens shouldBe 14L + 17L
        }

        "cancelling the stream closes the HTTP response" {
            val stream = FixtureStream(events("stream-text.jsonl"))
            val (service, _) = streamingService(stream)

            modelWith(service).stream(Prompt(UserMessage("Count to 5"))).take(2).collectList().block()

            stream.closed shouldBe true
        }

        "a 4xx when opening the stream surfaces as NonTransientAiException" {
            val service = mockk<ResponseService>()
            every { service.createStreaming(any<ResponseCreateParams>()) } throws
                BadRequestException
                    .builder()
                    .headers(Headers.builder().build())
                    .error(
                        ErrorObject
                            .builder()
                            .code(null)
                            .message("echoed user content")
                            .param("tools")
                            .type("invalid_request_error")
                            .build(),
                    ).build()

            val error = shouldThrow<NonTransientAiException> { modelWith(service).stream(Prompt(UserMessage("Hi"))).blockLast() }

            error.message shouldBe "400 BAD_REQUEST from POST $endpoint"
        }

        "an error event in the stream is classified by its code, not by the 200 status of the stream" {
            fun streamFailure(code: String) =
                SseException
                    .builder()
                    .statusCode(200)
                    .headers(Headers.builder().build())
                    .error(
                        ErrorObject
                            .builder()
                            .code(code)
                            .message("echoed user content")
                            .param(null)
                            .type(code)
                            .build(),
                    ).build()
            val prompt = Prompt(UserMessage("Count to 5"))
            val textEvents = events("stream-text.jsonl").take(6)

            val (transientService, _) = streamingService(FixtureStream(textEvents, streamFailure("server_error")))
            val transient = shouldThrow<TransientAiException> { modelWith(transientService).stream(prompt).blockLast() }
            transient.message shouldBe "OpenAI Responses stream error server_error"

            val (permanentService, _) = streamingService(FixtureStream(textEvents, streamFailure("invalid_prompt")))
            val permanent = shouldThrow<NonTransientAiException> { modelWith(permanentService).stream(prompt).blockLast() }
            permanent.message shouldBe "OpenAI Responses stream error invalid_prompt"
            permanent.message shouldNotContain "echoed user content"
        }

        "an I/O failure is transient" {
            val service = mockk<ResponseService>()
            every { service.create(any<ResponseCreateParams>()) } throws OpenAIIoException("connection reset")

            shouldThrow<TransientAiException> { modelWith(service).call(Prompt(UserMessage("Hi"))) }
            verify(exactly = 1) { service.create(any<ResponseCreateParams>()) }
        }
    })
