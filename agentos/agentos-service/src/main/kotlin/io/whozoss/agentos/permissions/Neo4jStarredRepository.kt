package io.whozoss.agentos.permissions

import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import mu.KLogging

/**
 * Neo4j implementation of [StarredRepository].
 *
 * Delegates to [CaseNodeNeo4jRepository] which owns all Cypher queries
 * for the `[:STARRED]` relationship on Case nodes.
 *
 * If starred is ever needed on another entity type, introduce a dedicated
 * `*NodeNeo4jRepository` for that type rather than making this generic.
 */
class Neo4jStarredRepository(
    private val caseNodeNeo4jRepository: CaseNodeNeo4jRepository,
) : StarredRepository {
    companion object : KLogging()

    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean =
        try {
            when (entityType) {
                EntityType.CASE -> when (starred) {
                    true -> caseNodeNeo4jRepository.mergeStarred(userId, entityId) > 0
                    false -> caseNodeNeo4jRepository.deleteStarred(userId, entityId) > 0
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
                        val starred = row["starred"] as Boolean
                        val relation =
                            if (PermissionRelation.ADMIN.name in relations) {
                                PermissionRelation.ADMIN
                            } else {
                                PermissionRelation.MEMBER
                            }
                        caseId to DirectRelation(relation, starred)
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
