package io.biznet.agentos.caseEvent

import io.biznet.agentos.sdk.entity.EntityService
import io.biznet.agentos.sdk.model.CaseEvent
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
