package io.whozoss.agentos.caseFlow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.FilesystemEntityRepository
import java.nio.file.Path
import java.util.UUID

/**
 * File-system implementation of [CaseRepository].
 *
 * Storage layout: `<dataDir>/cases/<projectId>/<caseId>.json`
 */
class FilesystemCaseRepository(
    dataDir: Path,
    objectMapper: ObjectMapper,
) : CaseRepository,
    EntityRepository<CaseModel, UUID> by FilesystemEntityRepository(
        rootDir = dataDir.resolve("cases"),
        entityClass = CaseModel::class.java,
        objectMapper = objectMapper,
        parentIdExtractor = { it.projectId },
        comparator = compareBy { it.metadata.created },
    )
