package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.FilesystemEntityRepository
import java.nio.file.Path
import java.util.UUID

/**
 * File-system implementation of [IntegrationConfigRepository].
 *
 * Storage layout: `<dataDir>/integration-configs/<namespaceId>/<configId>.json`
 */
class FilesystemIntegrationConfigRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : IntegrationConfigRepository,
    EntityRepository<IntegrationConfig, UUID> by FilesystemEntityRepository(
        rootDir = dataDir.resolve("integration-configs"),
        entityClass = IntegrationConfig::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    )
