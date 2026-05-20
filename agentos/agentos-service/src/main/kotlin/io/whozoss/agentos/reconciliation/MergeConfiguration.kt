package io.whozoss.agentos.reconciliation

import io.whozoss.agentos.aiProvider.AiProviderMergeStrategy
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires per-entity beans of [ConfigMergeService].
 *
 * Spring cannot derive parameterised reconciliation beans from a single `@Service` declaration
 * (Kotlin type erasure: the container has no way to disambiguate `ConfigLookup<IntegrationConfig>`
 * from `ConfigLookup<AiProvider>` at injection time). Each entity therefore registers
 * its bean explicitly here.
 */
@Configuration
class MergeConfiguration {
    @Bean
    fun integrationConfigMergeService(
        lookup: IntegrationConfigService,
        mergeStrategy: IntegrationConfigMergeStrategy,
    ): ConfigMergeService<IntegrationConfig> = ConfigMergeService(lookup, mergeStrategy)

    @Bean
    fun aiProviderReconciliationService(
        lookup: AiProviderService,
        mergeStrategy: AiProviderMergeStrategy,
    ): ConfigMergeService<AiProvider> = ConfigMergeService(lookup, mergeStrategy)

}
