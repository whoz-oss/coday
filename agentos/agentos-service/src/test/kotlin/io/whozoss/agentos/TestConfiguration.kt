package io.whozoss.agentos

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * Provides beans that are needed in the test application context but are not
 * auto-configured in the test profile (e.g. because no real AI provider is wired).
 *
 * Spring Boot 4.x still auto-configures [ObjectMapper] via [JacksonAutoConfiguration]
 * when spring-boot-starter-web is on the classpath, but the full-context integration
 * test needs it explicitly declared to avoid ordering issues.
 */
@TestConfiguration
class TestConfiguration {
    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}
