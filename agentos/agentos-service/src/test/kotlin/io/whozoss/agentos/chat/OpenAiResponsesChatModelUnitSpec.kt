package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.openai.client.OpenAIClient
import com.openai.core.jsonMapper
import com.openai.core.http.Headers
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.models.ErrorObject
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
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
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

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

        "an I/O failure is transient" {
            val service = mockk<ResponseService>()
            every { service.create(any<ResponseCreateParams>()) } throws OpenAIIoException("connection reset")

            shouldThrow<TransientAiException> { modelWith(service).call(Prompt(UserMessage("Hi"))) }
            verify(exactly = 1) { service.create(any<ResponseCreateParams>()) }
        }
    })
