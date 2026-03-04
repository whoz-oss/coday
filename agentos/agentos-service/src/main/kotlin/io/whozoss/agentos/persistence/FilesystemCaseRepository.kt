package io.whozoss.agentos.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseFlow.CaseModel
import io.whozoss.agentos.caseFlow.CaseRepository
import mu.KLogging
import java.nio.file.Path
import java.util.UUID

/**
 * File-system implementation of [CaseRepository].
 *
 * Storage layout:
 * ```
 * <dataDir>/cases/<projectId>/<caseId>.json
 * ```
 *
 * Cases are ordered by creation time (metadata.created) within each project.
 *
 * This bean is registered by [PersistenceConfiguration] when
 * `agentos.persistence.enabled=true`.  The [io.whozoss.agentos.caseFlow.InMemoryCaseRepository] bean is
 * excluded at that point so there is exactly one [CaseRepository] in the
 * application context.
 */
class FilesystemCaseRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : FilesystemEntityRepository<CaseModel, UUID>(
        rootDir = dataDir.resolve("cases"),
        entityClass = CaseModel::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { it.projectId },
        comparator = compareBy { it.metadata.created },
    ),
    CaseRepository {
    companion object : KLogging()
}
