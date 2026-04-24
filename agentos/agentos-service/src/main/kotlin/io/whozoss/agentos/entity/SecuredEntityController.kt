package io.whozoss.agentos.entity

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.BlockingPermissionService
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Controller sécurisé abstrait pour les endpoints d'entités avec vérification automatique des permissions.
 *
 * Cette classe étend EntityController en ajoutant des vérifications de permissions sur toutes
 * les opérations CRUD. Les permissions sont vérifiées via PermissionService avant d'exécuter
 * toute opération.
 *
 * Principes de sécurité appliqués :
 * - 404 Not Found pour les opérations READ quand l'utilisateur n'a pas la permission (cache l'existence)
 * - 403 Forbidden pour les opérations WRITE/DELETE quand l'utilisateur n'a pas la permission
 * - Filtrage automatique des listes pour ne retourner que les entités autorisées
 * - Support du bypass super-admin (géré par PermissionService)
 *
 * @param EntityType Le type d'entité du domaine (doit implémenter Entity)
 * @param ParentIdentifier Le type d'identifiant parent (typiquement UUID)
 * @param ResourceType Le type de ressource/DTO HTTP retourné et consommé par les endpoints
 * @property service Le service d'entité pour les opérations CRUD
 * @property userService Le service utilisateur pour obtenir l'utilisateur courant
 * @property permissionService Le service de permissions pour les vérifications d'accès
 */
abstract class SecuredEntityController<EntityType : Entity, ParentIdentifier, ResourceType>(
    service: EntityService<EntityType, ParentIdentifier>,
    protected val userService: UserService,
    protected val permissionService: BlockingPermissionService
) : EntityController<EntityType, ParentIdentifier, ResourceType>(service) {

    companion object : KLogging()

    /**
     * Retourne le type d'entité pour les vérifications de permissions.
     * Doit correspondre au label Neo4j de l'entité (ex: "Case", "Namespace", "AgentConfig").
     *
     * @return Le nom du type d'entité
     */
    abstract fun getEntityType(): String

    /**
     * GET /{id} — obtenir une seule entité par son ID.
     * Vérifie la permission READ et retourne 404 si l'utilisateur n'a pas accès.
     */
    override fun getById(@PathVariable id: UUID): ResourceType {
        val entity = service.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")

        val userId = userService.getCurrentUser().id.toString()
        if (!permissionService.hasPermission(userId, getEntityType(), id.toString(), Action.READ)) {
            // 404 pour cacher l'existence de l'entité
            throw ResourceNotFoundException("Entity not found: $id")
        }

        logger.debug { "User $userId accessed ${getEntityType()} $id" }
        return toResource(entity)
    }

    /**
     * POST /by-ids — obtenir plusieurs entités par leurs IDs.
     * Filtre les résultats pour ne retourner que les entités avec permission READ.
     */
    override fun getByIds(@RequestBody ids: List<UUID>): List<ResourceType> {
        val userId = userService.getCurrentUser().id.toString()
        val entities = service.findByIds(ids)

        return entities
            .filter { entity ->
                val hasPermission = permissionService.hasPermission(
                    userId,
                    getEntityType(),
                    entity.id.toString(),
                    Action.READ
                )
                if (!hasPermission) {
                    logger.debug { "Filtered out ${getEntityType()} ${entity.id} for user $userId - no READ permission" }
                }
                hasPermission
            }
            .map { toResource(it) }
    }

    /**
     * GET /by-parentId/{parentId} — lister toutes les entités appartenant à un parent.
     * Filtre les résultats pour ne retourner que les entités avec permission READ.
     */
    override fun listByParent(@PathVariable parentId: ParentIdentifier): List<ResourceType> {
        val userId = userService.getCurrentUser().id.toString()
        val entities = service.findByParent(parentId)

        return entities
            .filter { entity ->
                val hasPermission = permissionService.hasPermission(
                    userId,
                    getEntityType(),
                    entity.id.toString(),
                    Action.READ
                )
                if (!hasPermission) {
                    logger.debug { "Filtered out ${getEntityType()} ${entity.id} for user $userId - no READ permission" }
                }
                hasPermission
            }
            .map { toResource(it) }
    }

    /**
     * POST — créer une nouvelle entité.
     * Vérifie la permission WRITE sur le parent (si applicable).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: ResourceType): ResourceType {
        val userId = userService.getCurrentUser().id.toString()
        val domainEntity = toDomain(resource)

        // Pour la création, on vérifie généralement la permission sur le parent
        // Les sous-classes peuvent override cette méthode pour une logique spécifique
        checkCreatePermission(userId, domainEntity)

        val created = service.create(domainEntity)
        logger.info { "User $userId created ${getEntityType()} ${created.id}" }

        return toResource(created)
    }

    /**
     * PUT /{id} — mettre à jour une entité existante.
     * Vérifie la permission WRITE sur l'entité.
     */
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: ResourceType
    ): ResourceType {
        val userId = userService.getCurrentUser().id.toString()

        // Vérifier que l'entité existe
        service.findById(id)
            ?: throw ResourceNotFoundException("Entity not found: $id")

        // Vérifier la permission WRITE
        if (!permissionService.hasPermission(userId, getEntityType(), id.toString(), Action.WRITE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val updated = service.update(toDomain(resource))
        logger.info { "User $userId updated ${getEntityType()} $id" }

        return toResource(updated)
    }

    /**
     * DELETE /{id} — supprimer (soft-delete) une seule entité.
     * Vérifie la permission DELETE sur l'entité.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(@PathVariable id: UUID) {
        val userId = userService.getCurrentUser().id.toString()

        // Vérifier que l'entité existe
        val exists = service.findById(id) != null
        if (!exists) {
            throw ResourceNotFoundException("Entity not found: $id")
        }

        // Vérifier la permission DELETE
        if (!permissionService.hasPermission(userId, getEntityType(), id.toString(), Action.DELETE)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
        }

        val deleted = service.delete(id)
        if (deleted) {
            logger.info { "User $userId deleted ${getEntityType()} $id" }
        }
    }

    /**
     * Vérifie les permissions pour la création d'une entité.
     * Par défaut, cette méthode ne fait rien. Les sous-classes doivent l'override
     * pour implémenter leur logique spécifique (ex: vérifier WRITE sur le namespace parent).
     *
     * @param userId L'ID de l'utilisateur créant l'entité
     * @param entity L'entité à créer
     * @throws ResponseStatusException avec status 403 si l'utilisateur n'a pas la permission
     */
    protected open fun checkCreatePermission(userId: String, entity: EntityType) {
        // Fail-closed: deny creation by default. Subclasses must explicitly override
        // with their own logic to allow creation (e.g., check WRITE on parent namespace).
        throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied")
    }
}