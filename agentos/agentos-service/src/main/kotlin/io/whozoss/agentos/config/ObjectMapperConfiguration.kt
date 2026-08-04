package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Jackson configuration for AgentOS.
 *
 * Provides centralized [ObjectMapper] beans for JSON and YAML serialization/deserialization.
 */
@Configuration
class ObjectMapperConfiguration {
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
