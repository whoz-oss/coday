package io.whozoss.agentos.reconciliation

import io.whozoss.agentos.aiModel.AiModelLookup
import io.whozoss.agentos.aiModel.AiModelMergeStrategy
import io.whozoss.agentos.aiProvider.AiProviderLookup
import io.whozoss.agentos.aiProvider.AiProviderMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigLookup
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.sdk.aiProvider.AiModel
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
        lookup: IntegrationConfigLookup,
        mergeStrategy: IntegrationConfigMergeStrategy,
    ): ConfigMergeService<IntegrationConfig> = ConfigMergeService(lookup, mergeStrategy)

    @Bean
    fun aiProviderReconciliationService(
        lookup: AiProviderLookup,
        mergeStrategy: AiProviderMergeStrategy,
    ): ConfigMergeService<AiProvider> = ConfigMergeService(lookup, mergeStrategy)

    @Bean
    fun aiModelReconciliationService(
        lookup: AiModelLookup,
        mergeStrategy: AiModelMergeStrategy,
    ): ConfigMergeService<AiModel> = ConfigMergeService(lookup, mergeStrategy)
}
