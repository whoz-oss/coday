package io.biznet.agentos

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(
    exclude = [org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration::class],
)
class AgentosApplication

fun main(args: Array<String>) {
    runApplication<AgentosApplication>(*args)
}
