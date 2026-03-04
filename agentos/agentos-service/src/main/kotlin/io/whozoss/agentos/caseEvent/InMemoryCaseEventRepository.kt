package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of CaseEventRepository.
 *
 * Active only when `agentos.persistence.in-memory=true` (or the `in-memory` profile).
 * The default mode is file-system persistence via [io.whozoss.agentos.persistence.FilesystemCaseEventRepository].
 */
@Repository
@ConditionalOnProperty(prefix = "agentos.persistence", name = ["in-memory"], havingValue = "true")
class InMemoryCaseEventRepository :
    InMemoryEntityRepository<CaseEvent, UUID>(
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    ),
    CaseEventRepository
