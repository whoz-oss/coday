package io.biznet.agentos.orchestration.substep.intention

import io.biznet.agentos.thought.Intention
import io.biznet.agentos.thought.Channel
import io.biznet.agentos.thought.ToolChoiceGeneration
import io.biznet.agentos.tool.Functionality
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.stereotype.Service

/**
 * Service responsible for generating intentions based on the current thought context and user input.
 * Utilizes LLM to interpret the user's underlying needs and determine the next course of action.
 */
@Service
class IntentionGenerator {
    fun generateIntention(
        channel: Channel,
        chatClient: ChatClient,
        tools: List<Functionality>,
    ): Intention {
        val userMessage = if(channel.cases.last().steps.isEmpty()) {
            UserMessage(
                """
Knowing the following available tools:
${tools.joinToString("\n") { "* $it" }}

Here is my new query:
<request>
${channel.cases.last().message}
</request>

make a concise explanation of what is the next logic step to take (and so which tool to call now to complete the step to achieve a satisfactory answer to the request request)
                """.trimIndent(),
            )
        } else {
            UserMessage(
                """
Knowing the following available tools:
${tools.joinToString("\n") { "* $it" }}

And based on all those previous steps make a concise explanation of what is the next logic step to take ( so which tool to call now to complete the step to achieve a satisfactory answer to the request request)
                """.trimIndent(),
            )
        }

        return Intention(
            userMessage,
            chatClient.prompt(Prompt(channel.getMessages() + userMessage)).call().chatResponse()!!,
        )
    }

    fun getToolName(
        channel: Channel,
        intention: Intention,
        chatClient: ChatClient,
        tools: List<Functionality>,
    ): ToolChoiceGeneration {
        val user =
            UserMessage(
                "Based on your explanation please give me the corresponding tool to use from the following list: ${tools.map { it.name }}",
            )

        return chatClient
            .prompt(
                Prompt(channel.getMessages() + intention.getMessages() + user),
            ).call()
            .responseEntity(ToolChoice::class.java)
            .let { responseEntity ->
                ToolChoiceGeneration(
                    user,
                    responseEntity.response!!,
                    responseEntity.entity!!.toolName
                )
            }
    }
}

data class ToolChoice(
    val toolName: String,
)
