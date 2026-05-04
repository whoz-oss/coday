package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityService
import java.util.UUID

/**
 * Service for managing [Schedule] entities.
 *
 * Schedules are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface ScheduleService : EntityService<Schedule, UUID>
