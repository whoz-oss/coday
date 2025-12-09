package io.biznet.agentos.orchestration

import io.biznet.agentos.common.InMemoryEntityRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of CaseEventRepository.
 *
 * Uses the generic InMemoryEntityRepository with:
 * - Entity ID: event.id
 * - Parent ID: event.caseId
 * - Ordering: by timestamp (oldest first)
 *
 * This implementation is suitable for development and testing.
 * For production, consider a persistent implementation (database, file system).
 */
@Repository
class InMemoryCaseEventRepository : InMemoryEntityRepository<CaseEvent, UUID>(
    entityIdExtractor = { it.id },
    parentIdExtractor = { it.caseId },
    comparator = compareBy { it.timestamp }
), CaseEventRepository
