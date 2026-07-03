package io.whozoss.agentos.reconciliation

import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigMergeStrategy
import io.whozoss.agentos.integrationConfig.IntegrationConfigService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires per-entity beans of [ConfigMergeService].
 *
 * Spring cannot derive parameterised reconciliation beans from a single `@Service` declaration
 * (Kotlin type erasure: the container has no way to disambiguate `ConfigLookup<IntegrationConfig>`
 * from `ConfigLookup<AiProvider>` at injection time). Each entity therefore registers
 * its bean explicitly here.
 *
 * Note: [AiProvider] overlay resolution was migrated to [io.whozoss.agentos.aiProvider.AiProviderServiceImpl.resolveProvider]
 * which uses a single-query fetch + in-memory fold, matching the [IntegrationConfigService.findEffective] pattern.
 * No [ConfigMergeService] bean is needed for [io.whozoss.agentos.sdk.aiProvider.AiProvider].
 */
@Configuration
class MergeConfiguration {
    @Bean
    fun integrationConfigMergeService(
        lookup: IntegrationConfigService,
        mergeStrategy: IntegrationConfigMergeStrategy,
    ): ConfigMergeService<IntegrationConfig> = ConfigMergeService(lookup, mergeStrategy)
}
