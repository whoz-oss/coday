package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.sdk.entity.EntityService
import io.whozoss.agentos.sdk.model.CaseEvent
import java.util.*

/**
 * Service for managing CaseEvent entities.
 *
 * Extends EntityService with no additional methods.
 * Parent type is UUID representing the caseId.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 */
interface CaseEventService : EntityService<CaseEvent, UUID>
