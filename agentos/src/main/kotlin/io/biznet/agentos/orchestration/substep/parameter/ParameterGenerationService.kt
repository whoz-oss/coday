package io.biznet.agentos.orchestration.substep.parameter

import io.biznet.agentos.thought.*
import io.biznet.agentos.tool.Functionality
import io.biznet.agentos.tool.Parameter
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

@Service
class ParameterGenerationService {
    fun <T : Parameter> generate(
        channel: Channel,
        intention: Intention,
        toolGeneration: ToolChoiceGeneration,
        functionality: Functionality,
        clazz: Class<T>,
        chatClient: ChatClient,
        additionalContext: String? = null,
    ): ParameterGeneration {
        val user = UserMessage(buildPrompt(functionality, additionalContext))

        return try {
            val response =
                chatClient
                    .prompt(
                        Prompt(channel.getMessages() + intention.getMessages() + toolGeneration.getMessages() + user),
                    ).call()
                    .responseEntity(clazz)

            ParameterGeneration(
                user,
                response,
                null,
            )
        } catch (e: Exception) {
            // TODO fail add something to the parameter
            throw IllegalStateException(e)
        }
    }

    fun buildPrompt(
        functionality: Functionality,
        additionalContext: String? = null,
    ): String =
        """
Based On the fact that you want to use the ${functionality.name} which is described as:
${functionality.description}

You need to produce the parameter for it${additionalContext?.let {
            ", here is some additional context that may help you build it more correctly:\n$it"
        } ?: ""}
        """.trimIndent()
}
