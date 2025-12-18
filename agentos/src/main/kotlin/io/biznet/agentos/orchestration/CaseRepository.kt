package io.biznet.agentos.orchestration

import java.util.UUID

/**
 * Repository for CaseModel persistence.
 *
 * Parent type is UUID representing the projectId.
 */
interface CaseRepository : EntityRepository<CaseModel, UUID>
