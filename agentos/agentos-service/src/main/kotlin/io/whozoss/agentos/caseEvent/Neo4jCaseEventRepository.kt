package io.whozoss.agentos.caseEvent

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [CaseEventRepository].
 *
 * Each [CaseEvent] subtype is stored as a node with two labels: the primary
 * `:CaseEvent` label and a secondary label matching the subtype name (e.g.
 * `:MessageEvent`). SDN uses the secondary label to instantiate the correct
 * [CaseEventNode] subclass when reading from the graph.
 *
 * All fields are stored as explicit node properties — no JSON payload.
 * Audit fields managed by Spring Data (e.g. @LastModifiedDate) therefore
 * round-trip correctly without any post-deserialisation override.
 *
 * Parent type is [UUID] representing the caseId.
 */
open class Neo4jCaseEventRepository(
    private val caseEventNodeNeo4jRepository: CaseEventNodeNeo4jRepository,
    private val mapper: CaseEventNodeMapper,
    private val childLinkService: Neo4jChildLinkService,
) : CaseEventRepository {
    override fun save(entity: CaseEvent): CaseEvent =
        caseEventNodeNeo4jRepository
            .save(mapper.fromDomain(entity))
            .also { childLinkService.link("CaseEvent", it.id, "Case", it.caseId) }
            .let { mapper.toDomain(it) }
            .also { logger.debug { "[Neo4jCaseEventRepository] Saved ${entity.type.value} event ${entity.id} for case ${entity.caseId}" } }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<CaseEvent> =
        caseEventNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { mapper.toDomain(it) }

    override fun findByParent(parentId: UUID): List<CaseEvent> =
        caseEventNodeNeo4jRepository
            .findActiveByCaseId(parentId.toString())
            .map { mapper.toDomain(it) }

    override fun delete(id: UUID): Boolean =
        caseEventNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                caseEventNodeNeo4jRepository.save(mapper.withRemoved(node, true))
                logger.debug { "[Neo4jCaseEventRepository] Soft-deleted event $id" }
                true
            } ?: false

    @Transactional
    override fun deleteByParent(parentId: UUID): Int {
        val active = caseEventNodeNeo4jRepository.findActiveByCaseId(parentId.toString())
        caseEventNodeNeo4jRepository.saveAll(active.map { mapper.withRemoved(it, true) })
        logger.debug { "[Neo4jCaseEventRepository] Soft-deleted ${active.size} events for case $parentId" }
        return active.size
    }

    override fun findLastMessageTimestamps(caseIds: Collection<UUID>): Map<UUID, Instant> {
        val rows = caseEventNodeNeo4jRepository.findLastMessageTimestamps(caseIds.map { it.toString() })
        return rows.associate { row ->
            UUID.fromString(row["caseId"] as String) to (row["lastMessageAt"] as Instant)
        }
    }

    companion object : KLogging()
}
