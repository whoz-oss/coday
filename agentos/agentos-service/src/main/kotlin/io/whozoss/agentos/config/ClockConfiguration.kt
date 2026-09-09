package io.whozoss.agentos.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Provides a UTC [Clock] bean for injection throughout the application.
 *
 * Declared with [ConditionalOnMissingBean] so tests can substitute a fixed clock
 * without conflicts. The Neo4j persistence configuration also declares this bean
 * (for the case where it is the only active configuration), but this class
 * ensures the bean is available in all profiles including `test` (in-memory).
 */
@Configuration
class ClockConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun utcClock(): Clock = Clock.systemUTC()
}
