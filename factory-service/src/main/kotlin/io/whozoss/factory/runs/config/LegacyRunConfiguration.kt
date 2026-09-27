package io.whozoss.factory.runs.config

import io.whozoss.factory.runs.service.LegacyRunService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wiring of the legacy workflow JSONL runs aggregate.
 *
 * This bean used to be defined by the Forge configuration; it is core
 * infrastructure (the `/api/runs` surface) and is therefore wired here, reading
 * [LegacyRunProperties] and never the optional Forge plugin's properties.
 */
@Configuration
class LegacyRunConfiguration {

    @Bean
    fun legacyRunService(properties: LegacyRunProperties): LegacyRunService =
        LegacyRunService(
            runsDir = properties.dir,
            runEntry = properties.entry,
            agentosUrl = properties.agentosUrl,
            jiraBaseUrl = properties.jiraBaseUrl,
            jiraEmail = properties.jiraEmail,
            jiraApiToken = properties.jiraApiToken,
        )
}
