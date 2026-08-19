package io.whozoss.agentos.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

/**
 * Jackson configuration for AgentOS.
 *
 * Spring Boot's [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration]
 * would normally auto-configure a single `jacksonObjectMapper` bean via
 * [Jackson2ObjectMapperBuilder], applying `spring.jackson.*` properties automatically.
 * However, its `@ConditionalOnMissingBean(ObjectMapper)` guard fires as soon as ANY
 * `ObjectMapper` bean is present — which [yamlMapper] and [yamlExportMapper] already
 * satisfy. As a result the auto-configured JSON bean is never created, leaving
 * only the two YAML beans in the context and causing `NoUniqueBeanDefinitionException`
 * on every injection site that expects the primary JSON mapper.
 *
 * **Fix**: declare [jacksonObjectMapper] explicitly here, using the same
 * [Jackson2ObjectMapperBuilder] that Spring Boot's auto-configuration would use.
 * This ensures `spring.jackson.*` properties (e.g. `write-dates-as-timestamps: false`,
 * `sort-properties-alphabetically: true`) are applied. The [KotlinModule] is registered
 * automatically by the `kotlin-spring` Gradle plugin via its own
 * [org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer].
 *
 * Two additional YAML mappers are declared as separate beans for filesystem I/O.
 */
@Configuration
class ObjectMapperConfiguration {

    /**
     * The primary JSON [ObjectMapper] for HTTP serialization.
     *
     * Declared explicitly because [yamlMapper] and [yamlExportMapper] satisfy Spring Boot's
     * `@ConditionalOnMissingBean(ObjectMapper)` guard, preventing the auto-configured bean
     * from being created. Using [Jackson2ObjectMapperBuilder] ensures all `spring.jackson.*`
     * properties and registered [org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer]s
     * are applied — identical behaviour to what auto-configuration would produce.
     *
     * Marked `@Primary` so that injection sites that expect the JSON mapper resolve
     * by type without needing a `@Qualifier`. The two YAML mappers ([yamlMapper] and
     * [yamlExportMapper]) are the special cases and carry `@Qualifier` at their
     * injection sites.
     */
    @Bean
    @Primary
    fun jacksonObjectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper = builder.build()

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
    fun yamlMapper(): ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * YAML mapper for exporting entities as downloadable YAML files.
     *
     * Configured for clean, human-readable output:
     * - No `---` document start marker ([YAMLGenerator.Feature.WRITE_DOC_START_MARKER] disabled)
     * - Dates as ISO-8601 strings, not numeric arrays ([SerializationFeature.WRITE_DATES_AS_TIMESTAMPS] disabled, [JavaTimeModule] registered)
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
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
