package io.biznet.agentos.orchestration

import io.biznet.agentos.orchestration.substep.intention.IntentionGenerator
import io.biznet.agentos.orchestration.substep.parameter.InsideParameter
import io.biznet.agentos.orchestration.substep.parameter.InsideParameterContext
import io.biznet.agentos.thought.Case
import io.biznet.agentos.thought.Step
import io.biznet.agentos.thought.Chanel
import io.biznet.agentos.tool.Parameter
import io.biznet.agentos.tool.Tool
import io.biznet.agentos.tool.answer.AnswerParameter
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class Orchestrator(
    val intentionGenerator: IntentionGenerator,
    val tools: List<Tool<out Parameter, out InsideParameter>>,
) {
    val functionalities = tools.map { it.functionality }
    val conversations: MutableMap<UUID, Chanel> = mutableMapOf()

    fun orchestrate(
        chatClient: ChatClient,
        message: String,
        conversationId: UUID,
    ): String {
        val chanel =
            conversations[conversationId]?.also { thought ->
                if(thought.cases.last().steps.last().toolChoice.toolName == "Answer") {
                    thought.cases.add(Case(message))
                } else if (thought.cases.last().steps.last().toolResponse == null) {
                    thought.cases
                        .last()
                        .steps
                        .last()
                        .toolResponse = message
                }
            }
                ?: run {
                    Chanel.initThought(message).also { conversations[conversationId] = it }
                }

        getNextStep(chanel, chatClient)

        while (chanel.cases
                .lastOrNull()
                ?.steps
                ?.lastOrNull()
                ?.toolChoice
                ?.toolName != "Answer"
        ) {
            getNextStep(chanel, chatClient)
        }

        return (chanel.cases
            .lastOrNull()
            ?.steps
            ?.lastOrNull()?.parameter?.responseEntity!!.entity as AnswerParameter).answer
    }

    fun hasId(id: UUID): Boolean = conversations.contains(id)

    private fun getNextStep(
        chanel: Chanel,
        chatClient: ChatClient,
    ): Step {
        val intention =
            intentionGenerator.generateIntention(
                chanel = chanel,
                chatClient = chatClient,
                tools = functionalities,
            )
        println("Intention:")
        println(intention.chatResponse.result.output)

        val toolGeneration = intentionGenerator.getToolName(chanel, intention, chatClient, functionalities)

        val tool = tools.first { it.functionality.name == toolGeneration.toolName }
        println("Tool:" + toolGeneration.toolName)

        val parameter = tool.generateParameter(chanel, intention, toolGeneration, chatClient)

        @Suppress("UNCHECKED_CAST")
        val response =
            (tool as Tool<Parameter, InsideParameter>).execute(
                parameter.responseEntity.entity!! as Parameter,
                tool.getInsideParameter(InsideParameterContext()),
            )

        val step =
            Step(
                intention = intention,
                toolChoice = toolGeneration,
                parameter = parameter,
                toolResponse = response.response,
            )

        chanel.cases.last().steps.add(step)

        return step
    }
}
