package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * In-memory implementation of [CaseRepository].
 *
 * Active only when `agentos.persistence.mode=in-memory`.
 * The default mode is file-system persistence via [FilesystemCaseRepository].
 */
@Repository
@ConditionalOnProperty(name = ["agentos.persistence.mode"], havingValue = "in-memory")
class InMemoryCaseRepository :
    CaseRepository,
    EntityRepository<Case, UUID> by InMemoryEntityRepository(
        parentIdExtractor = { it.namespaceId },
        comparator = compareBy { it.metadata.id },
    )
