package io.biznet.agentos.tools.service

import org.springframework.ai.tool.annotation.Tool


internal class AgentTools {
    @Tool(description = "Get the current date and time in the user's timezone")
    fun delegateToAgent(agentName: String): String = "réponse de l'agent ${agentName}"

    @Tool(description = "Ask complementary information to user")
    fun askUser(message: String): String = "peux-tu poser la question suivante à l'utilisateur: $message"

}