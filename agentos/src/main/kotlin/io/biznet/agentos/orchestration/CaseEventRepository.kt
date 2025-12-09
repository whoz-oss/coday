package io.biznet.agentos.orchestration

import io.biznet.agentos.common.EntityRepository
import java.util.UUID

/**
 * Repository for CaseEvent persistence.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 *
 * Parent type is UUID representing the caseId.
 */
interface CaseEventRepository : EntityRepository<CaseEvent, UUID>
