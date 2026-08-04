package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Jackson configuration for AgentOS.
 *
 * Provides centralized [ObjectMapper] beans for JSON and YAML serialization/deserialization.
 *
 * Three beans are declared explicitly so that [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration]
 * does not need to run — its `jacksonObjectMapper` factory method is `@ConditionalOnMissingBean(ObjectMapper)`, so
 * it is skipped as soon as any [ObjectMapper] bean exists. Rather than relying on Boot auto-config, we declare
 * `jacksonObjectMapper` ourselves with a `@Primary` marker, which is the name used by `@Qualifier("jacksonObjectMapper")`
 * throughout the persistence configuration.
 */
@Configuration
class ObjectMapperConfiguration {
    /**
     * Primary JSON mapper — equivalent to the bean Spring Boot would auto-configure.
     *
     * Named `jacksonObjectMapper` so that `@Qualifier("jacksonObjectMapper")` in
     * [io.whozoss.agentos.config.Neo4jPersistenceConfiguration] resolves correctly
     * even when Boot's own auto-configuration is skipped.
     */
    @Bean
    @Primary
    fun jacksonObjectMapper(): ObjectMapper =
        ObjectMapper()
            .findAndRegisterModules()
            .registerModule(KotlinModule.Builder().build())

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
    fun yamlMapper(): ObjectMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    /**
     * YAML mapper for exporting entities as downloadable YAML files.
     *
     * Configured for clean, human-readable output:
     * - No `---` document start marker
     * - No Jackson type tags
     * - No timestamps (dates as ISO strings)
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
    fun yamlExportMapper(): ObjectMapper =
        ObjectMapper(YAMLFactory.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
