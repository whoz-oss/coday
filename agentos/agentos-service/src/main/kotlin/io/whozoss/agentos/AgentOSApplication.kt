package io.whozoss.agentos

import io.whozoss.agentos.config.PersistenceConfigProperties
import io.whozoss.agentos.schedule.SchedulerConfig
import io.whozoss.agentos.service.config.AgentOsPluginsConfigProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Main Spring Boot application for Agent OS.
 */
@SpringBootApplication(
    exclude = [
        org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration::class,
    ],
)
@EnableScheduling
@EnableConfigurationProperties(
    AgentOsPluginsConfigProperties::class,
    PersistenceConfigProperties::class,
    SchedulerConfig::class,
)
class AgentOSApplication

fun main(args: Array<String>) {
    forceLogback()
    runApplication<AgentOSApplication>(*args)
}

/**
 * Forces SLF4J to bind to Logback before any logging initialisation occurs.
 *
 * Neo4j 2026.x ships org.neo4j:neo4j-slf4j-provider which registers
 * SLF4JLogBridge as an SLF4J 2.0 service provider. When it wins the
 * ServiceLoader race over Logback, Spring Boot's LogbackLoggingSystem fails:
 *   "LoggerFactory is not a Logback LoggerContext"
 *
 * Must be called before [runApplication] — Spring Boot initialises logging
 * during context startup and the system property must be set before that point.
 */
private fun forceLogback() {
    System.setProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider")
}
