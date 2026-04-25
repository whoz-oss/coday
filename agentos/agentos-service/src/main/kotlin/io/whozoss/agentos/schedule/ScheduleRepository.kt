package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityRepository
import java.util.UUID

/**
 * Repository for [Schedule] persistence.
 *
 * Schedules are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface ScheduleRepository : EntityRepository<Schedule, UUID>
