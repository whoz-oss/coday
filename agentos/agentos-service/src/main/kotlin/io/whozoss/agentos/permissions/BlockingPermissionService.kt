package io.whozoss.agentos.permissions

import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component

/**
 * Wrapper synchrone pour PermissionService pour utilisation dans les controllers Spring MVC.
 *
 * Cette classe permet d'utiliser PermissionService (qui utilise des coroutines) dans un contexte
 * synchrone comme les controllers Spring MVC standards. Elle utilise runBlocking pour exécuter
 * les suspend functions de manière bloquante.
 *
 * Note: Dans un contexte de production idéal, il serait préférable d'utiliser Spring WebFlux
 * et des controllers réactifs pour éviter le blocage des threads.
 *
 * @property permissionService Le service de permissions asynchrone à wrapper
 */
@Component
class BlockingPermissionService(
    private val permissionService: PermissionService
) {
    /**
     * Version synchrone de hasPermission.
     * Bloque le thread actuel jusqu'à ce que la vérification de permission soit terminée.
     *
     * @see PermissionService.hasPermission
     */
    fun hasPermission(
        userId: String,
        entityType: String,
        entityId: String,
        action: Action
    ): Boolean = runBlocking {
        permissionService.hasPermission(userId, entityType, entityId, action)
    }

    /**
     * Version synchrone de grantPermission.
     * Bloque le thread actuel jusqu'à ce que l'octroi de permission soit terminé.
     *
     * @see PermissionService.grantPermission
     */
    fun grantPermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ) = runBlocking {
        permissionService.grantPermission(userId, entityType, entityId, relation)
    }

    /**
     * Version synchrone de revokePermission.
     * Bloque le thread actuel jusqu'à ce que la révocation de permission soit terminée.
     *
     * @see PermissionService.revokePermission
     */
    fun revokePermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ) = runBlocking {
        permissionService.revokePermission(userId, entityType, entityId, relation)
    }

    /**
     * Version synchrone de listUsersWithPermission.
     * Bloque le thread actuel jusqu'à ce que la liste soit récupérée.
     *
     * @see PermissionService.listUsersWithPermission
     */
    fun listUsersWithPermission(
        entityType: String,
        entityId: String,
        relation: PermissionRelation? = null
    ): List<String> = runBlocking {
        permissionService.listUsersWithPermission(entityType, entityId, relation)
    }

    /**
     * Version synchrone de listEntitiesForUser.
     * Bloque le thread actuel jusqu'à ce que la liste soit récupérée.
     *
     * @see PermissionService.listEntitiesForUser
     */
    fun listEntitiesForUser(
        userId: String,
        entityType: String,
        action: Action
    ): List<String> = runBlocking {
        permissionService.listEntitiesForUser(userId, entityType, action)
    }
}