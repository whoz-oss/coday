package io.biznet.agentos.orchestration

import io.biznet.agentos.common.EntityService
import java.util.UUID

/**
 * Service for managing CaseEvent entities.
 *
 * Extends EntityService with no additional methods.
 * Parent type is UUID representing the caseId.
 *
 * Implementation must ensure that findByParent returns events ordered by timestamp (oldest first).
 */
interface ICaseEventService : EntityService<CaseEvent, UUID>
