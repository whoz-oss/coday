package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.caseEvent.CaseEventRepository
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
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
    private val sdnRepo: CaseEventNeo4jRepository,
    private val objectMapper: ObjectMapper,
) : CaseEventRepository {
    override fun save(entity: CaseEvent): CaseEvent {
        val node = CaseEventNode.fromDomain(entity, objectMapper)
        val saved = sdnRepo.save(node)
        logger.debug { "[Neo4jCaseEventRepository] Saved ${saved.type} event ${saved.id} for case ${saved.caseId}" }
        return saved.toDomain(objectMapper)
    }

    override fun findByIds(ids: Collection<UUID>): List<CaseEvent> {
        val stringIds = ids.map { it.toString() }
        return sdnRepo
            .findAllById(stringIds)
            .filter { !it.removed }
            .map { it.toDomain(objectMapper) }
    }

    override fun findByParent(parentId: UUID): List<CaseEvent> =
        sdnRepo
            .findActiveByCaseId(parentId.toString())
            .map { it.toDomain(objectMapper) }

    override fun delete(id: UUID): Boolean {
        val node = sdnRepo.findById(id.toString()).orElse(null) ?: return false
        if (node.removed) return false
        sdnRepo.save(node.copy(removed = true))
        logger.debug { "[Neo4jCaseEventRepository] Soft-deleted event $id" }
        return true
    }

    override fun deleteByParent(parentId: UUID): Int {
        val active = sdnRepo.findActiveByCaseId(parentId.toString())
        active.forEach { sdnRepo.save(it.copy(removed = true)) }
        logger.debug { "[Neo4jCaseEventRepository] Soft-deleted ${active.size} events for case $parentId" }
        return active.size
    }

    companion object : KLogging()
}
