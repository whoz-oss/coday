package io.whozoss.agentos.permissions

import mu.KLogging

/**
 * Neo4j implementation of [StarredRepository].
 *
 * Delegates to [PermissionNodeNeo4jRepository] which owns all Cypher queries
 * on the permission/starred graph.
 */
class Neo4jStarredRepository(
    private val permissionNodeRepository: PermissionNodeNeo4jRepository,
) : StarredRepository {
    companion object : KLogging()

    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean =
        try {
            when (starred) {
                true -> permissionNodeRepository.mergeStarred(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType.label,
                ) > 0
                false -> permissionNodeRepository.deleteStarred(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType.label,
                ) > 0
            }
        } catch (e: Exception) {
            logger.error(e) { "Error setting starred=$starred for user=$userId on $entityType:$entityId" }
            throw e
        }

    override fun listStarred(userId: String, entityType: EntityType): Map<String, DirectRelation> =
        try {
            val result = mutableMapOf<String, DirectRelation>()
            for (row in permissionNodeRepository.findDirectRelations(userId, entityType.label)) {
                // Each row is "id|relation|starred" (see findDirectRelations).
                val parts = row.split('|')
                if (parts.size != 3) continue
                val id = parts[0]
                val relation = PermissionRelation.valueOf(parts[1])
                val isStarred = parts[2].toBoolean()
                val existing = result[id]
                // A user may hold both ADMIN and MEMBER edges on the same entity — ADMIN wins.
                val mergedRelation =
                    if (existing?.relation == PermissionRelation.ADMIN || relation == PermissionRelation.ADMIN) {
                        PermissionRelation.ADMIN
                    } else {
                        PermissionRelation.MEMBER
                    }
                result[id] = DirectRelation(mergedRelation, (existing?.starred ?: false) || isStarred)
            }
            result
        } catch (e: Exception) {
            logger.error(e) { "Error listing starred for user=$userId, type=$entityType" }
            emptyMap()
        }
}
