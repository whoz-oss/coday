package io.whozoss.agentos.reconciliation

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigLookup
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires per-entity beans of [ConfigReconciliationService].
 *
 * Spring cannot derive parameterised reconciliation beans from a single `@Service` declaration
 * (Kotlin type erasure: the container has no way to disambiguate `ConfigLookup<IntegrationConfig>`
 * from a future `ConfigLookup<AiProvider>` at injection time). Each entity therefore registers
 * its bean explicitly here. Story 6.3 will append a similar bean for `AiProvider` / `AiModel`.
 */
@Configuration
class ReconciliationConfiguration {
    @Bean
    fun integrationConfigReconciliationService(
        lookup: IntegrationConfigLookup,
        mergeStrategy: IntegrationConfigMergeStrategy,
    ): ConfigReconciliationService<IntegrationConfig> = ConfigReconciliationService(lookup, mergeStrategy)
}
