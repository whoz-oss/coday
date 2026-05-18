package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/** Test-only in-memory implementation of [IntegrationConfigRepository]. */
class InMemoryIntegrationConfigRepository :
    IntegrationConfigRepository,
    EntityRepository<IntegrationConfig, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.name },
    )
