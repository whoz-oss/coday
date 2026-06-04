package io.whozoss.agentos.config

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.domain.AuditorAware
import java.util.Optional

/**
 * Overrides [AgentOsAuditorAware] in test context.
 *
 * The production [AgentOsAuditorAware] relies on [org.springframework.web.context.request.RequestContextHolder]
 * which is unavailable in persistence tests (no HTTP request). This bean provides a
 * deterministic auditor UUID so that @CreatedBy / @LastModifiedBy annotations are exercised.
 */
@TestConfiguration
class TestAuditConfiguration {
    @Bean
    @Primary
    fun testAuditorAware(): AuditorAware<String> =
        AuditorAware { Optional.of(TEST_AUDITOR_ID) }

    companion object {
        const val TEST_AUDITOR_ID = "00000000-0000-0000-0000-000000000042"
    }
}
