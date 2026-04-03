package io.whozoss.agentos

import io.whozoss.agentos.config.PersistenceConfigProperties
import io.whozoss.agentos.service.config.AgentOsPluginsConfigProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Main Spring Boot application for Agent OS.
 */
@SpringBootApplication(
    exclude = [org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration::class],
)
@EnableConfigurationProperties(AgentOsPluginsConfigProperties::class, PersistenceConfigProperties::class)
class AgentOSApplication

fun main(args: Array<String>) {
    // Neo4j 2026.x ships org.neo4j:neo4j-slf4j-provider which registers
    // SLF4JLogBridge as an SLF4J 2.0 service provider. When it wins the
    // ServiceLoader race over Logback, Spring Boot's LogbackLoggingSystem fails:
    //   "LoggerFactory is not a Logback LoggerContext"
    // Setting slf4j.provider before any logging initialisation forces SLF4J
    // to use Logback regardless of classpath ordering or IDE classpath assembly.
    System.setProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider")
    runApplication<AgentOSApplication>(*args)
}
