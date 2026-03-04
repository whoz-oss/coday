package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.entity.EntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [CaseRepository].
 *
 * Active only when `agentos.persistence.in-memory=true`.
 * The default mode is file-system persistence via [FilesystemCaseRepository].
 */
@Repository
@ConditionalOnProperty(prefix = "agentos.persistence", name = ["in-memory"], havingValue = "true")
class InMemoryCaseRepository :
    CaseRepository,
    EntityRepository<CaseModel, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.projectId },
        comparator = compareBy { it.metadata.id },
    )
