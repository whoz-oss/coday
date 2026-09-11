package io.whozoss.agentos.permissions

import org.springframework.stereotype.Service

@Service
class FavoriteServiceImpl(
    private val favoriteRepository: FavoriteRepository,
) : FavoriteService {

    override fun setFavorite(userId: String, entityType: EntityType, entityId: String, favorite: Boolean): Boolean =
        favoriteRepository.setFavorite(userId, entityType, entityId, favorite)

    override fun listDirectRelations(userId: String, entityType: EntityType): Map<String, DirectRelation> =
        favoriteRepository.listDirectRelations(userId, entityType)
}
