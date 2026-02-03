package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.model.CaseEvent
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
class InMemoryCaseEventRepository :
    InMemoryEntityRepository<CaseEvent, UUID>(
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    ),
    CaseEventRepository
