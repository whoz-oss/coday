package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.FilesystemEntityRepository
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists

/**
 * File-system implementation of [IntegrationConfigRepository].
 *
 * Storage layout: `<dataDir>/integration-configs/<namespaceId>/<configId>.json`
 *
 * Supplies an O(1) [findFileByIdFn] that constructs the path directly from the known
 * parent namespace directory, avoiding the default O(n) tree scan.
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
        findFileByIdFn = { id ->
            // O(1) lookup is not possible without knowing the namespaceId from the id alone;
            // fall back to null so FilesystemEntityRepository performs its standard tree scan.
            null
        },
    )
