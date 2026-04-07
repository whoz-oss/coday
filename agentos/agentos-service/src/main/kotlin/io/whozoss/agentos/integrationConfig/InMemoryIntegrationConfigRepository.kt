package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [IntegrationConfigRepository].
 *
 * Active only when `agentos.persistence.mode=in-memory`.
 * The default mode is file-system persistence via [FilesystemIntegrationConfigRepository].
 *
 * Entities are sorted by [IntegrationConfig.name] within each namespace.
 */
@Repository
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory")
class InMemoryIntegrationConfigRepository :
    IntegrationConfigRepository,
    EntityRepository<IntegrationConfig, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    )
