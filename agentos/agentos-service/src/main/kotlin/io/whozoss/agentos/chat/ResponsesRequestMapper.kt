package io.whozoss.agentos.chat

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.core.JsonValue
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseFunctionToolCall
import com.openai.models.responses.ResponseInputContent
import com.openai.models.responses.ResponseInputImage
import com.openai.models.responses.ResponseInputItem
import com.openai.models.responses.ResponseInputText
import com.openai.models.responses.Tool
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.content.Media
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.definition.ToolDefinition
import java.util.Base64

/**
 * Maps a Spring AI conversation to an OpenAI Responses API request.
 *
 * - System messages become `instructions`; the other messages become input items, in order.
 * - Assistant tool calls become `function_call` items and tool responses `function_call_output`
 *   items, paired by call id.
 * - Images attached to user messages become `input_image` parts.
 * - `store` is always false: AgentOS replays the conversation on every call, so OpenAI has no
 *   reason to keep the response.
 * - Temperature and reasoning effort are sent only when configured, so the model defaults apply.
 */
internal object ResponsesRequestMapper {
    private val schemaReader = ObjectMapper()
    private val schemaType = object : TypeReference<Map<String, Any?>>() {}

    fun toParams(
        messages: List<Message>,
        options: OpenAiChatOptions,
        toolDefinitions: List<ToolDefinition>,
    ): ResponseCreateParams {
        val builder =
            ResponseCreateParams
                .builder()
                .model(requireNotNull(options.model) { "No model configured for a Responses API call" })
                .inputOfResponse(messages.filterNot { it.messageType == MessageType.SYSTEM }.flatMap(::toInputItems))
                .store(false)
        instructionsOf(messages)?.let { builder.instructions(it) }
        options.temperature?.let { builder.temperature(it) }
        (options.maxCompletionTokens ?: options.maxTokens)?.let { builder.maxOutputTokens(it.toLong()) }
        options.reasoningEffort?.let {
            builder.reasoning(Reasoning.builder().effort(ReasoningEffort.of(it)).build())
        }
        if (toolDefinitions.isNotEmpty()) builder.tools(toolDefinitions.map(::toTool))
        return builder.build()
    }

    private fun instructionsOf(messages: List<Message>): String? =
        messages
            .filter { it.messageType == MessageType.SYSTEM }
            .mapNotNull { it.text }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

    private fun toInputItems(message: Message): List<ResponseInputItem> =
        when (message) {
            is ToolResponseMessage -> {
                message.responses.map(::toFunctionCallOutput)
            }

            is AssistantMessage -> {
                buildList {
                    message.text?.takeIf { it.isNotBlank() }?.let {
                        add(easyMessage(EasyInputMessage.Role.ASSISTANT, it))
                    }
                    message.toolCalls.forEach { add(toFunctionCall(it)) }
                }
            }

            is UserMessage -> {
                listOf(toUserItem(message))
            }

            else -> {
                listOf(easyMessage(EasyInputMessage.Role.USER, message.text.orEmpty()))
            }
        }

    private fun toUserItem(message: UserMessage): ResponseInputItem {
        if (message.media.isEmpty()) return easyMessage(EasyInputMessage.Role.USER, message.text.orEmpty())
        val parts =
            buildList {
                message.text?.takeIf { it.isNotBlank() }?.let {
                    add(ResponseInputContent.ofInputText(ResponseInputText.builder().text(it).build()))
                }
                message.media.forEach { add(toImagePart(it)) }
            }
        return ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage
                .builder()
                .role(EasyInputMessage.Role.USER)
                .contentOfResponseInputMessageContentList(parts)
                .build(),
        )
    }

    private fun toImagePart(media: Media): ResponseInputContent {
        // Media built from a URI keeps it as a String; every other payload is readable as bytes.
        val url =
            when (val data = media.data) {
                is String -> data
                else -> "data:${media.mimeType};base64,${Base64.getEncoder().encodeToString(media.dataAsByteArray)}"
            }
        return ResponseInputContent.ofInputImage(
            ResponseInputImage
                .builder()
                .imageUrl(url)
                .detail(ResponseInputImage.Detail.AUTO)
                .build(),
        )
    }

    private fun easyMessage(
        role: EasyInputMessage.Role,
        content: String,
    ): ResponseInputItem = ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(content).build())

    private fun toFunctionCall(toolCall: AssistantMessage.ToolCall): ResponseInputItem =
        ResponseInputItem.ofFunctionCall(
            ResponseFunctionToolCall
                .builder()
                .callId(toolCall.id())
                .name(toolCall.name())
                .arguments(toolCall.arguments())
                .build(),
        )

    private fun toFunctionCallOutput(response: ToolResponseMessage.ToolResponse): ResponseInputItem =
        ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput
                .builder()
                .callId(response.id())
                .output(response.responseData())
                .build(),
        )

    private fun toTool(definition: ToolDefinition): Tool {
        val parameters = FunctionTool.Parameters.builder()
        schemaReader
            .readValue(definition.inputSchema(), schemaType)
            .forEach { (key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)) }
        return Tool.ofFunction(
            FunctionTool
                .builder()
                .name(definition.name())
                .description(definition.description())
                .parameters(parameters.build())
                // Strict mode rejects open schemas (optional fields, additional properties) that
                // AgentOS tools routinely declare.
                .strict(false)
                .build(),
        )
    }
}
