package io.whozoss.agentos.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.KotlinModule

/**
 * Jackson 3 configuration for AgentOS.
 *
 * The primary JSON mapper is declared explicitly from the [JsonMapper.Builder] that Spring Boot's
 * `JacksonAutoConfiguration` prepares, so every `spring.jackson.*` property and registered
 * `JsonMapperBuilderCustomizer` is applied (Kotlin module included, found on the classpath), exactly as the
 * auto-configured bean would be. Declaring it here keeps one unambiguous `@Primary` JSON mapper next to the
 * two YAML mappers, which are themselves [ObjectMapper]s.
 *
 * Jackson 3 mappers are immutable: each one is fully configured through its builder.
 * Java time support is built into Jackson 3 databind (no `JavaTimeModule` anymore).
 */
@Configuration
class ObjectMapperConfiguration {

    /**
     * The primary JSON mapper for HTTP serialization.
     *
     * Marked `@Primary` so that injection sites that expect the JSON mapper resolve by type without needing a
     * `@Qualifier`. The two YAML mappers ([yamlMapper] and [yamlExportMapper]) are the special cases and carry
     * `@Qualifier` at their injection sites.
     */
    @Bean
    @Primary
    fun jacksonObjectMapper(builder: JsonMapper.Builder): JsonMapper = builder.build()

    /**
     * YAML mapper for reading YAML files from the filesystem.
     *
     * Registered with the Kotlin module for proper data class support.
     * Used by:
     * - [io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository]
     * - [io.whozoss.agentos.integrationConfig.FilesystemIntegrationConfigRepository]
     * - [io.whozoss.agentos.prompt.FilesystemPromptRepository]
     */
    @Bean
    @Qualifier("yamlMapper")
    fun yamlMapper(): ObjectMapper =
        YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()

    /**
     * YAML mapper for exporting entities as downloadable YAML files.
     *
     * Configured for clean, human-readable output:
     * - No `---` document start marker ([YAMLWriteFeature.WRITE_DOC_START_MARKER] disabled)
     * - Dates as ISO-8601 strings, not numeric arrays ([DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS] disabled)
     * - No inclusion policy: each controller's [toExportModel] filters fields
     *   explicitly via `buildMap`, so nulls and empty values are never handed
     *   to Jackson in the first place.
     *
     * Used by the `export` endpoints of:
     * - [io.whozoss.agentos.agentConfig.AgentConfigController]
     * - [io.whozoss.agentos.integrationConfig.IntegrationConfigController]
     * - [io.whozoss.agentos.prompt.PromptController]
     */
    @Bean
    @Qualifier("yamlExportMapper")
    fun yamlExportMapper(): ObjectMapper =
        YAMLMapper.builder()
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()
}
