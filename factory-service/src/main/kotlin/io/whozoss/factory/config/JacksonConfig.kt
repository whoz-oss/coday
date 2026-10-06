package io.whozoss.factory.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Jackson configuration shared by the whole service.
 *
 * `null` fields are deliberately *kept* in the serialized output: the error
 * envelope contract `{ "error": { "code", "message", "details": null } }`
 * requires `details` to be present even when absent.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun factoryJacksonCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder
                .modulesToInstall(JavaTimeModule())
                .featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                )
        }
}
