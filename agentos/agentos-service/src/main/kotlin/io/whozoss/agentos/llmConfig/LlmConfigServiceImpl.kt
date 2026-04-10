package io.whozoss.agentos.llmConfig

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [LlmConfigService].
 *
 * [create] validates that at least one of namespaceId / userId is set, then enforces
 * the (namespaceId, userId, name) uniqueness constraint with a 409 on conflict.
 * [update] replaces the entity as-is; the controller is responsible for resolving
 * masked [LlmConfig.apiKey] values before calling this method.
 */
@Service
class LlmConfigServiceImpl(
    private val repository: LlmConfigRepository,
) : LlmConfigService {
    override fun create(entity: LlmConfig): LlmConfig {
        // Domain invariant — also checked in the entity init block, but we surface a
        // proper HTTP 400 here rather than letting an IllegalArgumentException bubble.
        if (entity.namespaceId == null && entity.userId == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "LlmConfig must be scoped to at least a namespace or a user",
            )
        }
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "An LLM config named '${entity.name}' already exists for this scope",
            )
        }
        return repository.save(entity)
    }

    override fun update(entity: LlmConfig): LlmConfig {
        findByNamespaceAndUserAndName(entity.namespaceId, entity.userId, entity.name)
            ?.takeIf { it.id != entity.id }
            ?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "An LLM config named '${entity.name}' already exists for this scope",
                )
            }
        return repository.save(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<LlmConfig> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<LlmConfig> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<LlmConfig> =
        repository.findByNamespaceId(namespaceId)

    override fun findByUserId(userId: UUID): List<LlmConfig> =
        repository.findByUserId(userId)

    override fun findByNamespaceAndUserAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): LlmConfig? {
        val candidates = when {
            namespaceId != null -> repository.findByNamespaceId(namespaceId)
            userId != null -> repository.findByUserId(userId)
            else -> emptyList()
        }
        return candidates.firstOrNull { it.namespaceId == namespaceId && it.userId == userId && it.name == name }
    }
}
