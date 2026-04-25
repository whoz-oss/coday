package io.whozoss.agentos.schedule

import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Delegates all persistence operations to [ScheduleRepository].
 */
@Service
class ScheduleServiceImpl(
    private val scheduleRepository: ScheduleRepository,
) : ScheduleService {
    override fun create(entity: Schedule): Schedule = scheduleRepository.save(entity)

    override fun update(entity: Schedule): Schedule = scheduleRepository.save(entity)

    override fun findByIds(ids: Collection<UUID>): List<Schedule> = scheduleRepository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<Schedule> = scheduleRepository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = scheduleRepository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = scheduleRepository.deleteByParent(parentId)
}
