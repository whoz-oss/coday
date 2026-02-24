package io.whozoss.agentos

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.whozoss.agentos.service.config.AgentOsPluginsConfigProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * Main Spring Boot application for Agent OS.
 */
@SpringBootApplication(
    exclude = [org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration::class],
)
@EnableConfigurationProperties(AgentOsPluginsConfigProperties::class)
class AgentOSApplication {

    @Bean
    fun agentOsOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("AgentOS API")
                .description("REST API for AgentOS — orchestration of AI agents via plugins")
                .version("0.0.1"),
        )
}

fun main(args: Array<String>) {
    runApplication<AgentOSApplication>(*args)
}
