package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.sdk.entity.EntityRepository
import java.util.UUID

/**
 * Repository for CaseModel persistence.
 *
 * Parent type is UUID representing the projectId.
 */
interface CaseRepository : EntityRepository<CaseModel, UUID>
