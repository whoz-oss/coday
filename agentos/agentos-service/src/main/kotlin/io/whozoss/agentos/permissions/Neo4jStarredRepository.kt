package io.whozoss.agentos.permissions

import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import mu.KLogging
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Neo4j implementation of [StarredRepository].
 *
 * Delegates to [CaseNodeNeo4jRepository] which owns all Cypher queries
 * for the `[:HAS_USER_CASE_STATE]` relationship on Case nodes.
 *
 * The legacy `[:STARRED]` plain edge has been replaced by the
 * `[:HAS_USER_CASE_STATE]` relationship-with-properties, which consolidates
 * both the favorite flag ([DirectRelation.favoriteAt]) and the read timestamp
 * ([DirectRelation.readAt]) on a single edge.
 */
class Neo4jStarredRepository(
    private val caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
) : StarredRepository {
    companion object : KLogging()

    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean =
        try {
            when (entityType) {
                EntityType.CASE -> when (starred) {
                    true -> caseNodeNeo4jRepository.mergeStarred(
                        userId = userId,
                        caseId = entityId,
                        favoriteAt = Instant.now(),
                    ) > 0
                    false -> caseNodeNeo4jRepository.deleteStarred(
                        userId = userId,
                        caseId = entityId,
                    ) > 0
                }
                else -> {
                    logger.warn { "setStarred not supported for entityType=$entityType" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error setting starred=$starred for user=$userId on $entityType:$entityId" }
            throw e
        }

    override fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation> =
        try {
            when (entityType) {
                EntityType.CASE ->
                    // Rows are already collapsed one-per-case by the Cypher query, so no manual
                    // de-duplication is needed. ADMIN wins when the user holds both ADMIN and MEMBER.
                    caseNodeNeo4jRepository.findDirectRelations(userId).associate { row ->
                        val caseId = row["caseId"] as String
                        val relations = (row["relations"] as List<*>).map { it.toString() }
                        // Temporal values come back from the Neo4j driver as ZonedDateTime in raw Map projections.
                        val favoriteAt = (row["favoriteAt"] as? ZonedDateTime)?.toInstant()
                        val readAt = (row["readAt"] as? ZonedDateTime)?.toInstant()
                        val relation =
                            if (PermissionRelation.ADMIN.name in relations) {
                                PermissionRelation.ADMIN
                            } else {
                                PermissionRelation.MEMBER
                            }
                        caseId to DirectRelation(relation = relation, favoriteAt = favoriteAt, readAt = readAt)
                    }
                else -> {
                    logger.warn { "listDirectRelations not supported for entityType=$entityType" }
                    emptyMap()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error listing direct relations for user=$userId, type=$entityType" }
            emptyMap()
        }
}
