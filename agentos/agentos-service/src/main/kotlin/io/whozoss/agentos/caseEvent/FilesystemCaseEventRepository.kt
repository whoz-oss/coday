package io.whozoss.agentos.caseEvent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.FilesystemEntityRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import java.nio.file.Path
import java.util.UUID

/**
 * File-system implementation of [CaseEventRepository].
 *
 * Storage layout: `<dataDir>/case-events/<caseId>/<eventId>.json`
 * Events are ordered by timestamp (oldest first).
 */
class FilesystemCaseEventRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : CaseEventRepository,
    EntityRepository<CaseEvent, UUID> by FilesystemEntityRepository(
        rootDir = dataDir.resolve("case-events"),
        entityClass = CaseEvent::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    )
