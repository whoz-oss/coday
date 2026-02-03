package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.entity.EntityRepository
import io.whozoss.agentos.sdk.model.CaseEvent
import java.util.UUID

/**
 * Repository for CaseEvent persistence.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 *
 * Parent type is UUID representing the caseId.
 */
interface CaseEventRepository : EntityRepository<CaseEvent, UUID>
