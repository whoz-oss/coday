package io.whozoss.agentos.integrationConfig

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [IntegrationConfigService].
 *
 * Delegates persistence to [IntegrationConfigRepository].
 *
 * Triple-mode invariant — `(namespaceId != null) OR (userId != null)` — is enforced on both
 * [create] and [update] (defence-in-depth, even if the controller already validates). A
 * violation surfaces as HTTP 400 to align with the [io.whozoss.agentos.aiProvider.AiProviderServiceImpl]
 * pattern referenced by story 6.1.
 *
 * Uniqueness on the (namespaceId, userId, name) triple is enforced on [create] (409 on
 * conflict) and on [update] when a rename would collide with another row in the same scope.
 */
@Service
class IntegrationConfigServiceImpl(
    private val repository: IntegrationConfigRepository,
) : IntegrationConfigService {
    override fun create(entity: IntegrationConfig): IntegrationConfig {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                conflictMessage(entity),
            )
        }
        return repository.save(entity)
    }

    override fun update(entity: IntegrationConfig): IntegrationConfig {
        requireScope(entity)
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    conflictMessage(entity),
                )
            }
        return repository.save(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<IntegrationConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<IntegrationConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): IntegrationConfig? = repository.findByTriple(namespaceId, userId, name)

    override fun findByNamespaceAndName(
        namespaceId: UUID,
        name: String,
    ): IntegrationConfig? = findByNamespaceAndUserAndName(namespaceId, null, name)

    private fun requireScope(entity: IntegrationConfig) {
        if (entity.namespaceId == null && entity.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "IntegrationConfig must be scoped to at least a namespace or a user",
            )
        }
    }

    private fun conflictMessage(entity: IntegrationConfig): String =
        "An integration config named '${entity.name}' already exists for this scope " +
            "(namespaceId=${entity.namespaceId}, userId=${entity.userId})"
}
