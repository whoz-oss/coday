package io.whozoss.agentos.permissions

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

    override fun setStarred(userId: String, entityType: EntityType, entityId: String, starred: Boolean): Boolean =
        starredRepository.setStarred(userId, entityType, entityId, starred)

    override fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation> =
        starredRepository.listDirectRelations(userId, entityType)
}
