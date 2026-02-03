package io.whozoss.agentos

import io.whozoss.agentos.config.properties.AgentOsPluginsConfigProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Main Spring Boot application for Agent OS.
 */
@SpringBootApplication
@EnableConfigurationProperties(AgentOsPluginsConfigProperties::class)
class AgentOSApplication

fun main(args: Array<String>) {
    runApplication<AgentOSApplication>(*args)
}
