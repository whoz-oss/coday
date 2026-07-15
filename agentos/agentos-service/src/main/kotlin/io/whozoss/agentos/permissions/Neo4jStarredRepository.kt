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
                EntityType.CASE -> {
                    val result = mutableMapOf<String, DirectRelation>()
                    for (row in caseNodeNeo4jRepository.findDirectRelations(userId)) {
                        // Each row is "caseId|relation|starred" (see CaseNodeNeo4jRepository.findDirectRelations).
                        val parts = row.split('|')
                        if (parts.size != 3) continue
                        val id = parts[0]
                        val relation = PermissionRelation.valueOf(parts[1])
                        val isStarred = parts[2].toBoolean()
                        val existing = result[id]
                        // A user may hold both ADMIN and MEMBER edges on the same case — ADMIN wins.
                        val mergedRelation =
                            if (existing?.relation == PermissionRelation.ADMIN || relation == PermissionRelation.ADMIN) {
                                PermissionRelation.ADMIN
                            } else {
                                PermissionRelation.MEMBER
                            }
                        result[id] = DirectRelation(mergedRelation, (existing?.starred ?: false) || isStarred)
                    }
                    result
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
