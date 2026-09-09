package io.whozoss.agentos.permissions

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service

@Service
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:embedded-neo4j}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:embedded-neo4j}' == 'embedded-neo4j'",
)
class FavoriteServiceImpl(
    private val favoriteRepository: FavoriteRepository,
) : FavoriteService {

    override fun setFavorite(userId: String, entityType: EntityType, entityId: String, favorite: Boolean): Boolean =
        favoriteRepository.setFavorite(userId, entityType, entityId, favorite)

    override fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation> =
        favoriteRepository.listDirectRelations(userId, entityType)
}
