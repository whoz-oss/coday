package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.sdk.aiProvider.AiProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [AiProviderService].
 *
 * [create] validates that at least one of namespaceId / userId is set, then enforces
 * the (namespaceId, userId, name) uniqueness constraint with a 409 on conflict.
 * [update] replaces the entity as-is; the controller is responsible for resolving
 * masked [AiProvider.apiKey] values before calling this method.
 */
@Service
class AiProviderServiceImpl(
    private val repository: AiProviderRepository,
) : AiProviderService {
    override fun create(entity: AiProvider): AiProvider {
        // Domain invariant — also checked in the entity init block, but we surface a
        // proper HTTP 400 here rather than letting an IllegalArgumentException bubble.
        if (entity.namespaceId == null && entity.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "AiProvider must be scoped to at least a namespace or a user",
            )
        }
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An AI provider named '${entity.name}' already exists for this scope",
            )
        }
        return repository.save(entity)
    }

    override fun update(entity: AiProvider): AiProvider {
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An AI provider named '${entity.name}' already exists for this scope",
                )
            }
        return repository.save(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<AiProvider> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<AiProvider> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AiProvider> = repository.findByNamespaceId(namespaceId)

    override fun findByUserId(userId: UUID): List<AiProvider> = repository.findByUserId(userId)

    override fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AiProvider? = repository.findByTriple(namespaceId, userId, name)
}
