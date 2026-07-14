package io.whozoss.agentos.permissions

import mu.KLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service

@Service
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class StarredServiceImpl(
    private val starredRepository: StarredRepository,
) : StarredService {
    companion object : KLogging()

    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean =
        try {
            starredRepository.setStarred(userId, entityType, entityId, starred)
        } catch (e: Exception) {
            logger.error(e) { "Failed to set starred=$starred for user=$userId on $entityType:$entityId" }
            throw e
        }

    override fun listStarred(userId: String, entityType: EntityType): Map<String, DirectRelation> =
        try {
            starredRepository.listStarred(userId, entityType)
        } catch (e: Exception) {
            logger.error(e) { "Failed to list starred for user=$userId, type=$entityType" }
            emptyMap()
        }
}
