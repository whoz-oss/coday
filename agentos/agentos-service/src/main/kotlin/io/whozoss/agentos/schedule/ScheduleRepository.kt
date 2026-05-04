package io.whozoss.agentos.schedule

import io.whozoss.agentos.entity.EntityRepository
import java.time.Instant
import java.util.UUID

/**
 * Repository for [Schedule] persistence.
 *
 * Schedules are scoped under a namespace — [parentId] is the namespace UUID.
 */
interface ScheduleRepository : EntityRepository<Schedule, UUID> {
    /**
     * Returns all enabled, non-removed schedules whose [Schedule.nextTriggerAt]
     * is at or before [now], ordered by [Schedule.nextTriggerAt] ascending.
     *
     * Schedules with a `null` [Schedule.nextTriggerAt] are excluded — they have
     * not yet been initialised and are not ready to fire.
     */
    fun findDueSchedules(now: Instant): List<Schedule>
}
