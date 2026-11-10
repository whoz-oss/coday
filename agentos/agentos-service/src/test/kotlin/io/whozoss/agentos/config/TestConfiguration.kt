package io.whozoss.agentos.config

import org.springframework.boot.test.context.TestConfiguration

/**
 * Provides beans that are needed in the test application context but are not
 * auto-configured in the test profile.
 *
 * The [ObjectMapper] beans ([ObjectMapperConfiguration.jacksonObjectMapper],
 * [ObjectMapperConfiguration.yamlMapper], [ObjectMapperConfiguration.yamlExportMapper])
 * are declared in the production [ObjectMapperConfiguration] and are always available
 * in the Spring context — no override needed here.
 */
@TestConfiguration
class TestConfiguration
