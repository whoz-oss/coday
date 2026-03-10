package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Case] persistence.
 *
 * Parent type is UUID representing the namespaceId.
 */
interface CaseRepository : EntityRepository<Case, UUID>
