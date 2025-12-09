package io.biznet.agentos.orchestration

import io.biznet.agentos.common.InMemoryEntityRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of CaseRepository.
 *
 * Uses the generic InMemoryEntityRepository with:
 * - Entity ID: case.id
 * - Parent ID: case.projectId
 * - Ordering: by ID (arbitrary but consistent)
 *
 * This implementation is suitable for development and testing.
 * For production, consider a persistent implementation (database, file system).
 */
@Repository
class InMemoryCaseRepository : InMemoryEntityRepository<CaseModel, UUID>(
    entityIdExtractor = { it.id },
    parentIdExtractor = { it.projectId },
    comparator = compareBy { it.id }
), CaseRepository
