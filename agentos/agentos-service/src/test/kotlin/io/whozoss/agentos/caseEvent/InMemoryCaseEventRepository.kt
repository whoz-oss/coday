package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.util.UUID

/** Test-only in-memory implementation of [CaseEventRepository]. */
class InMemoryCaseEventRepository :
    CaseEventRepository,
    EntityRepository<CaseEvent, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    )
