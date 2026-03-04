package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.sdk.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of CaseRepository.
 *
 * Active only when `agentos.persistence.in-memory=true` (or the `in-memory` profile).
 * The default mode is file-system persistence via [io.whozoss.agentos.persistence.FilesystemCaseRepository].
 */
@Repository
@ConditionalOnProperty(prefix = "agentos.persistence", name = ["in-memory"], havingValue = "true")
class InMemoryCaseRepository :
    InMemoryEntityRepository<CaseModel, UUID>(
        parentIdExtractor = { it.projectId },
        comparator = compareBy { it.metadata.id },
    ),
    CaseRepository
