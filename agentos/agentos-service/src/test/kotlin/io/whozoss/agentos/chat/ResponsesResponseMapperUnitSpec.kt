package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.node.ObjectNode
import com.openai.core.jsonMapper
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseStreamEvent
import com.openai.models.responses.ResponseUsage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException

/**
 * Fixtures under `openai-responses/` are real gpt-6-sol responses (`store: false`) captured from
 * the OpenAI Responses API, so parsing follows the actual wire format.
 */
class ResponsesResponseMapperUnitSpec :
    StringSpec({

        val mapper = jsonMapper()

        fun resource(name: String): String =
            requireNotNull(javaClass.classLoader.getResource("openai-responses/$name")) { name }.readText()

        fun response(
            name: String,
            edit: (ObjectNode) -> Unit = {},
        ): Response {
            val json = mapper.readTree(resource(name)) as ObjectNode
            edit(json)
            return mapper.treeToValue(json, Response::class.java)
        }

        fun events(name: String): List<ResponseStreamEvent> =
            resource(name).lines().filter { it.isNotBlank() }.map { mapper.readValue(it, ResponseStreamEvent::class.java) }

        "a text response maps to its text, a stop finish reason and native usage" {
            val chatResponse = ResponsesResponseMapper.toChatResponse(response("response-text.json"))

            chatResponse.result.output.text shouldBe "Hello."
            chatResponse.result.output.toolCalls.shouldBeEmpty()
            chatResponse.result.metadata.finishReason shouldBe "stop"
            chatResponse.metadata.model shouldBe "gpt-6-sol"
            chatResponse.metadata.usage.promptTokens shouldBe 12
            chatResponse.metadata.usage.nativeUsage.shouldBeInstanceOf<ResponseUsage>()
        }

        "function_call items map to tool calls with a tool_calls finish reason" {
            val chatResponse = ResponsesResponseMapper.toChatResponse(response("response-function-call.json"))

            val toolCalls = chatResponse.result.output.toolCalls
            toolCalls shouldHaveSize 1
            toolCalls[0].id() shouldBe "call_HoZNaI7y22Z0j5VoytjTJIuP"
            toolCalls[0].type() shouldBe "function"
            toolCalls[0].name() shouldBe "get_time"
            toolCalls[0].arguments() shouldBe "{}"
            chatResponse.result.metadata.finishReason shouldBe "tool_calls"
        }

        "a response cut by max_output_tokens reports a length finish reason, as AgentAdvanced expects" {
            val incomplete =
                response("response-text.json") {
                    it.put("status", "incomplete")
                    it.putObject("incomplete_details").put("reason", "max_output_tokens")
                }

            ResponsesResponseMapper.toChatResponse(incomplete).result.metadata.finishReason shouldBe "length"
        }

        "a failed response raises without exposing the provider message" {
            val failed =
                response("response-text.json") {
                    it.put("status", "failed")
                    it.putObject("error").put("code", "invalid_prompt").put("message", "echoed user content")
                }

            val error = shouldThrow<NonTransientAiException> { ResponsesResponseMapper.toChatResponse(failed) }
            error.message shouldBe "OpenAI Responses call ended with invalid_prompt"
            error.message shouldNotContain "echoed user content"
        }

        "a server_error failure is transient" {
            val failed =
                response("response-text.json") {
                    it.put("status", "failed")
                    it.putObject("error").put("code", "server_error").put("message", "try again")
                }

            shouldThrow<TransientAiException> { ResponsesResponseMapper.toChatResponse(failed) }
        }

        "a text stream yields one chunk per delta, then a text-free final chunk with usage" {
            val chunks = events("stream-text.jsonl").mapNotNull(ResponsesResponseMapper::toChunk)

            val text = chunks.dropLast(1).joinToString("") { it.result.output.text.orEmpty() }
            text shouldBe "1, 2, 3, 4, 5"
            val final = chunks.last()
            final.result.output.text shouldBe ""
            final.result.metadata.finishReason shouldBe "stop"
            final.metadata.usage.promptTokens shouldBe 17
            final.metadata.usage.completionTokens shouldBe 17
            chunks.dropLast(1).all { it.metadata.usage.promptTokens == 0 } shouldBe true
        }

        "a function call stream carries the tool call only on the final chunk" {
            val chunks = events("stream-function-call.jsonl").mapNotNull(ResponsesResponseMapper::toChunk)

            chunks shouldHaveSize 1
            chunks[0].result.output.toolCalls.map { it.name() } shouldBe listOf("get_time")
            chunks[0].result.metadata.finishReason shouldBe "tool_calls"
        }

        "a stream error event raises with its code only, transient for server errors" {
            fun errorEvent(code: String) =
                mapper.readValue(
                    """{"type":"error","code":"$code","message":"echoed user content","param":null,"sequence_number":3}""",
                    ResponseStreamEvent::class.java,
                )

            val error = shouldThrow<NonTransientAiException> { ResponsesResponseMapper.toChunk(errorEvent("invalid_prompt")) }
            error.message shouldBe "OpenAI Responses stream error invalid_prompt"
            shouldThrow<TransientAiException> { ResponsesResponseMapper.toChunk(errorEvent("server_error")) }
        }
    })
