package io.whozoss.agentos.testutil

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator

/**
 * Creates a [ObjectMapper] instance identical to the `yamlExportMapper` Spring bean
 * defined in [io.whozoss.agentos.config.ObjectMapperConfiguration].
 *
 * Used by controller unit tests that instantiate controllers directly (without Spring
 * context) and need to pass the export mapper as a constructor argument.
 */
fun yamlExportMapper(): ObjectMapper =
    ObjectMapper(YAMLFactory.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
