package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseEventRepository].
 *
 * [CaseEvent] is a sealed interface with ~15 subtypes and Jackson polymorphic
 * serialisation. Rather than annotating each subtype with @Node (which would
 * require Spring Data Neo4j in the SDK module), the full event JSON is stored
 * in the [CaseEventNode.payload] property. Only metadata and routing fields
 * are promoted to first-class node properties for Cypher queries.
 *
 * The [ObjectMapper] used here must be the same one configured with
 * [com.fasterxml.jackson.module.kotlin.KotlinModule] and the application's
 * Jackson configuration so that polymorphic deserialisation works correctly.
 *
 * Parent type is [UUID] representing the caseId.
 */
class Neo4jCaseEventRepository(
    private val sdnRepo: CaseEventNodeNeo4jRepository,
    private val objectMapper: ObjectMapper,
) : CaseEventRepository {
    override fun save(entity: CaseEvent): CaseEvent =
        sdnRepo
            .save(CaseEventNode.fromDomain(entity, objectMapper))
            .toDomain(objectMapper)
            .also { logger.debug { "[Neo4jCaseEventRepository] Saved ${entity.type.value} event ${entity.id} for case ${entity.caseId}" } }

    override fun findByIds(ids: Collection<UUID>): List<CaseEvent> =
        sdnRepo
            .findAllById(ids.map { it.toString() })
            .filter { !it.removed }
            .map { it.toDomain(objectMapper) }

    override fun findByParent(parentId: UUID): List<CaseEvent> =
        sdnRepo
            .findActiveByCaseId(parentId.toString())
            .map { it.toDomain(objectMapper) }

    override fun delete(id: UUID): Boolean =
        sdnRepo
            .findByIdOrNull(id.toString())
            ?.takeIf { !it.removed }
            ?.let { node ->
                sdnRepo.save(node.copy(removed = true))
                logger.debug { "[Neo4jCaseEventRepository] Soft-deleted event $id" }
                true
            } ?: false

    @Transactional
    override fun deleteByParent(parentId: UUID): Int {
        val active = sdnRepo.findActiveByCaseId(parentId.toString())
        sdnRepo.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jCaseEventRepository] Soft-deleted ${active.size} events for case $parentId" }
        return active.size
    }

    companion object : KLogging()
}
