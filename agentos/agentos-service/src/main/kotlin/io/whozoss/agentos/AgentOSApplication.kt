<<<<<<<< HEAD:agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/AgentOSApplication.kt
package io.whozoss.agentos

import io.whozoss.agentos.config.properties.AgentOsPluginsConfigProperties
========
package io.whozoss.agentos.service

import io.whozoss.agentos.service.config.AgentOsPluginsConfigProperties
>>>>>>>> origin/master:agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/service/AgentOSApplication.kt
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Main Spring Boot application for Agent OS.
 */
@SpringBootApplication(
    exclude = [org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration::class],
)
@EnableConfigurationProperties(AgentOsPluginsConfigProperties::class)
class AgentOSApplication

fun main(args: Array<String>) {
    runApplication<AgentOSApplication>(*args)
}
