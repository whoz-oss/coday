package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
import java.nio.file.Path
import java.util.UUID

/**
 * File-system implementation of [CaseEventRepository].
 *
 * Storage layout:
 * ```
 * <dataDir>/case-events/<caseId>/<eventId>.json
 * ```
 *
 * Events are ordered by timestamp (oldest first) within each case, consistent
 * with the contract defined on [CaseEventRepository].
 *
 * This bean is registered by [PersistenceConfiguration] when
 * `agentos.persistence.enabled=true`.
 */
class FilesystemCaseEventRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : FilesystemEntityRepository<CaseEvent, UUID>(
        rootDir = dataDir.resolve("case-events"),
        entityClass = CaseEvent::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { it.caseId },
        comparator = compareBy { it.timestamp },
    ),
    CaseEventRepository {
    companion object : KLogging()
}
