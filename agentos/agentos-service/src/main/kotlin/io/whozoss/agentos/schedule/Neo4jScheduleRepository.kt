package io.whozoss.agentos.schedule

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.schedule.IntervalSchedule
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [ScheduleRepository].
 *
 * Parent type is [UUID] representing the namespaceId.
 *
 * [intervalScheduleJson] is serialised/deserialised here via [objectMapper] so
 * the node class stays free of Jackson dependencies.
 */
open class Neo4jScheduleRepository(
    private val neo4jRepository: ScheduleNodeNeo4jRepository,
    private val objectMapper: ObjectMapper,
) : ScheduleRepository {

    private fun ScheduleNode.toDomainWithMapper(): Schedule =
        toDomain(intervalScheduleJson?.let { objectMapper.readValue(it, IntervalSchedule::class.java) })

    override fun save(entity: Schedule): Schedule =
        neo4jRepository
            .save(ScheduleNode.fromDomain(entity, objectMapper))
            .toDomainWithMapper()
            .also { logger.debug { "[Neo4jScheduleRepository] Saved schedule ${it.id} under namespace ${entity.namespaceId}" } }

    override fun findByIds(ids: Collection<UUID>): List<Schedule> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomainWithMapper() }

    override fun findByParent(parentId: UUID): List<Schedule> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomainWithMapper() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jScheduleRepository] Soft-deleted schedule $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jScheduleRepository] Soft-deleted ${active.size} schedules under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
