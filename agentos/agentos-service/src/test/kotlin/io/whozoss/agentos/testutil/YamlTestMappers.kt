package io.whozoss.agentos.testutil

import io.whozoss.agentos.config.ObjectMapperConfiguration
import tools.jackson.databind.ObjectMapper

/**
 * Creates a [ObjectMapper] instance identical to the `yamlExportMapper` Spring bean
 * defined in [ObjectMapperConfiguration].
 *
 * Used by controller unit tests that instantiate controllers directly (without Spring
 * context) and need to pass the export mapper as a constructor argument.
 */
fun yamlExportMapper(): ObjectMapper = ObjectMapperConfiguration().yamlExportMapper()
