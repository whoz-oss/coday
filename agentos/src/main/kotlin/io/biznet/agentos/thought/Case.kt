package io.biznet.agentos.thought

import org.springframework.ai.chat.client.ResponseEntity
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import java.util.*

data class Case(
    val message: String,
    val steps: MutableList<Step> = mutableListOf(),
) {
    fun getMessages(): List<Message> = steps.flatMap { it.getMessages() }
}

data class Step(
    val intention: Intention,
    val toolChoice: ToolChoiceGeneration,
    val parameter: ParameterGeneration,
    var toolResponse: String?,
) {
    fun getMessages(): List<Message> {
        val id = UUID.randomUUID().toString()
        return listOf(
            AssistantMessage(
                intention.chatResponse.result.output.text!!,
                emptyMap(),
                listOf(
                    AssistantMessage.ToolCall(
                        id,
                        "function",
                        toolChoice.toolName,
                        parameter.responseEntity.response!!
                            .result.output.text!!,
                    ),
                ),
            ),
            ToolResponseMessage(
                listOf(ToolResponseMessage.ToolResponse(id, toolChoice.toolName, toolResponse!!)),
            ),
        )
    }
}

data class Intention(
    val instructionMessage: UserMessage,
    val chatResponse: ChatResponse,
) {
    fun getMessages(): List<Message> = listOf(instructionMessage, chatResponse.result.output)
}

data class ToolChoiceGeneration(
    val instructionMessage: UserMessage,
    val chatResponse: ChatResponse,
    val toolName: String,
) {
    fun getMessages(): List<Message> = listOf(instructionMessage, chatResponse.result.output)
}

data class ParameterGeneration(
    val instructionMessage: UserMessage,
    val responseEntity: ResponseEntity<ChatResponse, *>,
    val issue: String?,
)
