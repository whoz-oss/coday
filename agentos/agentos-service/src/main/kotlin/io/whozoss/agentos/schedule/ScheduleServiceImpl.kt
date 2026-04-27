package io.whozoss.agentos.schedule

import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Delegates all persistence operations to [ScheduleRepository].
 *
 * On [create], [nextTriggerAt] is computed via [ScheduleTriggerCalculator] when
 * not already provided by the caller — this ensures the executor can detect the
 * schedule immediately on its next poll without a separate initialisation step.
 *
 * On [update], [nextTriggerAt] is recomputed when the trigger strategy ([Schedule.triggerAt]
 * or [Schedule.intervalSchedule]) has changed relative to the persisted version.
 * This keeps [nextTriggerAt] coherent after a user edits the schedule via the REST API.
 * When the strategy is unchanged the stored [nextTriggerAt] is preserved so the executor
 * cursor is not reset unnecessarily.
 */
@Service
class ScheduleServiceImpl(
    private val scheduleRepository: ScheduleRepository,
) : ScheduleService {
    override fun create(entity: Schedule): Schedule {
        val withNextTrigger = when {
            entity.nextTriggerAt != null -> entity
            else -> entity.copy(
                nextTriggerAt = ScheduleTriggerCalculator.computeInitialTriggerAt(entity, Instant.now())
            )
        }
        return scheduleRepository.save(withNextTrigger)
    }

    override fun update(entity: Schedule): Schedule {
        val existing = scheduleRepository.findByIds(listOf(entity.id)).firstOrNull()
        val strategyChanged = existing == null
            || existing.triggerAt != entity.triggerAt
            || existing.intervalSchedule != entity.intervalSchedule
        val withNextTrigger = when {
            !strategyChanged -> entity
            else -> entity.copy(
                nextTriggerAt = ScheduleTriggerCalculator.computeInitialTriggerAt(entity, Instant.now())
            )
        }
        return scheduleRepository.save(withNextTrigger)
    }

    override fun findByIds(ids: Collection<UUID>): List<Schedule> = scheduleRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<Schedule> = scheduleRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = scheduleRepository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = scheduleRepository.deleteByParent(parentId)
}
