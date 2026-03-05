package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Case] persistence.
 *
 * Parent type is UUID representing the projectId.
 */
interface CaseRepository : EntityRepository<Case, UUID>
