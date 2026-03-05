package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [CaseEventRepository].
 *
 * Active only when `agentos.persistence.in-memory=true`.
 * The default mode is file-system persistence via [FilesystemCaseEventRepository].
 */
@Repository
@ConditionalOnProperty(prefix = "agentos.persistence", name = ["in-memory"], havingValue = "true")
class InMemoryCaseEventRepository :
    CaseEventRepository,
    EntityRepository<CaseEvent, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    )
