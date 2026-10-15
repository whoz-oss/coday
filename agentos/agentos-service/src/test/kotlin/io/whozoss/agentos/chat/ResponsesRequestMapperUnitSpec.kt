package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.openai.core.jsonMapper
import com.openai.models.responses.ResponseCreateParams
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.content.Media
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.core.io.ByteArrayResource
import org.springframework.util.MimeTypeUtils

/**
 * Asserts the JSON body the OpenAI SDK would send for a mapped request, so the checks follow the
 * Responses API wire format rather than SDK accessors.
 */
class ResponsesRequestMapperUnitSpec :
    StringSpec({

        fun body(
            messages: List<Message>,
            options: OpenAiChatOptions = OpenAiChatOptions.builder().model("gpt-6-sol").build(),
            tools: List<ToolDefinition> = emptyList(),
        ): JsonNode {
            val params: ResponseCreateParams = ResponsesRequestMapper.toParams(messages, options, tools)
            return jsonMapper().valueToTree(params._body())
        }

        "system messages become instructions and are not sent as input items" {
            val json = body(listOf(SystemMessage("Namespace context"), SystemMessage("Agent rules"), UserMessage("Hi")))

            json["instructions"].asText() shouldBe "Namespace context\n\nAgent rules"
            json["input"].map { it["role"].asText() } shouldContainExactly listOf("user")
        }

        "conversation turns map to input items in order, tool calls paired with their outputs" {
            val toolCall = AssistantMessage.ToolCall("call_1", "function", "get_time", "{}")
            val json =
                body(
                    listOf(
                        UserMessage("What time is it?"),
                        AssistantMessage.builder().content("Let me check.").toolCalls(listOf(toolCall)).build(),
                        ToolResponseMessage
                            .builder()
                            .responses(listOf(ToolResponseMessage.ToolResponse("call_1", "get_time", "13:37")))
                            .build(),
                    ),
                )

            val input = json["input"]
            input.map { it["type"]?.asText() ?: "message" } shouldContainExactly
                listOf("message", "message", "function_call", "function_call_output")
            input[0]["role"].asText() shouldBe "user"
            input[0]["content"].asText() shouldBe "What time is it?"
            input[1]["role"].asText() shouldBe "assistant"
            input[1]["content"].asText() shouldBe "Let me check."
            input[2]["call_id"].asText() shouldBe "call_1"
            input[2]["name"].asText() shouldBe "get_time"
            input[2]["arguments"].asText() shouldBe "{}"
            input[3]["call_id"].asText() shouldBe "call_1"
            input[3]["output"].asText() shouldBe "13:37"
        }

        "an assistant message with only tool calls sends no empty assistant message" {
            val toolCall = AssistantMessage.ToolCall("call_1", "function", "get_time", "{}")
            val json = body(listOf(UserMessage("Time?"), AssistantMessage.builder().toolCalls(listOf(toolCall)).build()))

            json["input"].map { it["type"]?.asText() ?: "message" } shouldContainExactly listOf("message", "function_call")
        }

        "images attached to a user message become input_image parts with a base64 data URL" {
            // Built as MessageConversions.toSpringAiMedia does for tool images.
            val image = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(ByteArrayResource(byteArrayOf(1, 2, 3))).build()
            val json = body(listOf(UserMessage.builder().text("Describe this").media(image).build()))

            val content = json["input"][0]["content"]
            content.map { it["type"].asText() } shouldContainExactly listOf("input_text", "input_image")
            content[0]["text"].asText() shouldBe "Describe this"
            content[1]["image_url"].asText() shouldBe "data:image/png;base64,AQID"
        }

        "tool definitions become non-strict function tools carrying their JSON schema" {
            val schema = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            val json =
                body(
                    listOf(UserMessage("Read it")),
                    tools = listOf(ToolDefinition.builder().name("readFile").description("Read a file").inputSchema(schema).build()),
                )

            val tool = json["tools"][0]
            tool["type"].asText() shouldBe "function"
            tool["name"].asText() shouldBe "readFile"
            tool["description"].asText() shouldBe "Read a file"
            tool["strict"].asBoolean() shouldBe false
            tool["parameters"]["required"].map { it.asText() } shouldContainExactly listOf("path")
            tool["parameters"]["properties"]["path"]["type"].asText() shouldBe "string"
        }

        "store is always false and unset sampling or reasoning options are not sent" {
            val json = body(listOf(UserMessage("Hi")))

            json["model"].asText() shouldBe "gpt-6-sol"
            json["store"].asBoolean() shouldBe false
            json.has("temperature") shouldBe false
            json.has("reasoning") shouldBe false
            json.has("max_output_tokens") shouldBe false
            json.has("tools") shouldBe false
        }

        "configured temperature, completion limit and reasoning effort are sent" {
            val options =
                OpenAiChatOptions
                    .builder()
                    .model("gpt-6-astra")
                    .temperature(0.2)
                    .maxCompletionTokens(4096)
                    .reasoningEffort("high")
                    .build()

            val json = body(listOf(UserMessage("Hi")), options)

            json["temperature"].asDouble() shouldBe 0.2
            json["max_output_tokens"].asLong() shouldBe 4096L
            json["reasoning"]["effort"].asText() shouldBe "high"
        }
    })
