package io.whozoss.agentos.feedback

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.sdk.feedback.Feedback
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [FeedbackRepository].
 *
 * Each [Feedback] is stored as a `(:Feedback)-[:FEEDBACK_ON]->(:CaseEvent)` edge.
 * The edge is created via [Neo4jChildLinkService.link] after the node save, following
 * the same pattern used by [io.whozoss.agentos.caseEvent.Neo4jCaseEventRepository].
 *
 * Parent type is [UUID] representing the [Feedback.caseId].
 */
open class Neo4jFeedbackRepository(
    private val feedbackNodeNeo4jRepository: FeedbackNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : FeedbackRepository {
    @Transactional
    override fun save(entity: Feedback): Feedback =
        feedbackNodeNeo4jRepository
            .save(FeedbackNode.fromDomain(entity))
            .also { childLinkService.link("Feedback", it.id, "CaseEvent", it.caseEventId, "FEEDBACK_ON") }
            .toDomain()
            .also { logger.debug { "[Neo4jFeedbackRepository] Saved feedback ${entity.id} on event ${entity.caseEventId}" } }

    /**
     * Upsert: if [userId] is non-null and a non-removed feedback already exists for
     * that user + [entity.caseEventId], update it in-place (preserving the original `id`
     * and `created` timestamp so Spring Data auditing does not reset them).
     * Otherwise fall through to a plain [save].
     */
    @Transactional
    override fun upsert(
        entity: Feedback,
        userId: String,
    ): Feedback {
        val existing =
            feedbackNodeNeo4jRepository.findActiveByUserAndCaseEventId(
                caseEventId = entity.caseEventId.toString(),
                userId = userId,
            )

        return when {
            existing != null -> {
                logger.debug {
                    "[Neo4jFeedbackRepository] Updating existing feedback ${existing.id} " +
                        "for user $userId on event ${entity.caseEventId}"
                }
                // Carry forward the original id and created/createdBy so auditing does not
                // treat this as a new node. Only mutable fields are taken from the incoming entity.
                val updated =
                    existing.copy(
                        positive = entity.positive,
                        type = entity.type,
                        comment = entity.comment,
                    )
                feedbackNodeNeo4jRepository.save(updated).toDomain()
            }

            else -> {
                save(entity)
            }
        }
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Feedback> =
        feedbackNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<Feedback> =
        feedbackNodeNeo4jRepository
            .findActiveByCaseId(parentId.toString())
            .map { it.toDomain() }

    override fun findByCaseEventId(caseEventId: UUID): List<Feedback> =
        feedbackNodeNeo4jRepository
            .findActiveByCaseEventId(caseEventId.toString())
            .map { it.toDomain() }

    @Transactional
    override fun delete(id: UUID): Boolean =
        feedbackNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                feedbackNodeNeo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jFeedbackRepository] Soft-deleted feedback $id" }
                true
            } ?: false

    @Transactional
    override fun deleteByParent(parentId: UUID): Int {
        val active = feedbackNodeNeo4jRepository.findActiveByCaseId(parentId.toString())
        feedbackNodeNeo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jFeedbackRepository] Soft-deleted ${active.size} feedback entries for case $parentId" }
        return active.size
    }

    companion object : KLogging()
}
