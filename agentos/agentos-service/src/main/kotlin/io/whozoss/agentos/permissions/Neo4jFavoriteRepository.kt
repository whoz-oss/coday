package io.whozoss.agentos.permissions

import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import mu.KLogging
import java.time.ZonedDateTime

/**
 * Neo4j implementation of [FavoriteRepository].
 *
 * Delegates to [CaseNodeNeo4jRepository] which owns all Cypher queries
 * for the `[:WATCHES]` relationship on Case nodes.
 *
 * The legacy `[:STARRED]` plain edge has been replaced by the
 * `[:WATCHES]` relationship-with-properties, which consolidates
 * both the favorite flag ([DirectRelation.favorite]) and the read timestamp
 * ([DirectRelation.readAt]) on a single edge.
 */
class Neo4jFavoriteRepository(
    private val caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
) : FavoriteRepository {
    companion object : KLogging()

    override fun setFavorite(
        userId: String,
        entityType: EntityType,
        entityId: String,
        favorite: Boolean,
    ): Boolean =
        try {
            when (entityType) {
                EntityType.CASE -> {
                    when (favorite) {
                        true -> {
                            caseNodeNeo4jRepository.mergeFavorite(
                                userId = userId,
                                caseId = entityId,
                            ) > 0
                        }

                        false -> {
                            caseNodeNeo4jRepository.clearFavorite(
                                userId = userId,
                                caseId = entityId,
                            ) > 0
                        }
                    }
                }

                else -> {
                    logger.warn { "setFavorite not supported for entityType=$entityType" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error setting favorite=$favorite for user=$userId on $entityType:$entityId" }
            throw e
        }

    override fun listDirectRelations(
        userId: String,
        entityType: EntityType,
    ): Map<String, DirectRelation> =
        try {
            when (entityType) {
                EntityType.CASE -> {
                    // Rows are already collapsed one-per-case by the Cypher query, so no manual
                    // de-duplication is needed. ADMIN wins when the user holds both ADMIN and MEMBER.
                    caseNodeNeo4jRepository.findDirectRelations(userId).associate { row ->
                        val caseId = row["caseId"] as String
                        val relations = (row["relations"] as List<*>).map { it.toString() }
                        // Temporal values come back from the Neo4j driver as ZonedDateTime in raw Map projections.
                        val favorite = (row["favorite"] as? Boolean) ?: false
                        val readAt = (row["readAt"] as? ZonedDateTime)?.toInstant()
                        val relation =
                            if (PermissionRelation.ADMIN.name in relations) {
                                PermissionRelation.ADMIN
                            } else {
                                PermissionRelation.MEMBER
                            }
                        caseId to DirectRelation(relation = relation, favorite = favorite, readAt = readAt)
                    }
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
