package io.whozoss.agentos.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun agentOsOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("AgentOS API")
                .description("REST API for AgentOS — orchestration of AI agents via plugins")
                .version("0.0.1"),
        )
}
