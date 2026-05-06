package io.whozoss.agentos.aiModel

import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiModel
import mu.KLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Default implementation of [AiModelService].
 *
 * [create] resolves [AiModel.namespaceId] and [AiModel.userId] from the parent [AiProvider]
 * via [AiProviderService] and denormalises them onto the entity before saving, so that
 * namespace-scoped queries can be served without joining through [AiProvider].
 *
 * Uniqueness is enforced at TWO levels:
 *  1. **Per-provider alias** (applicative pre-check): two configs may share the same
 *     [AiModel.apiModelName] under the same provider (e.g. same model at different
 *     temperatures) as long as their aliases differ. When alias is null the check is
 *     skipped — null-alias configs are intentionally unconstrained so they can be
 *     resolved by apiName fallback at runtime.
 *  2. **Triple `(namespaceId, userId, coalesce(alias, apiName))`** (DB-level unique
 *     constraint via `tripleKey`): mirrors the IntegrationConfig / AiProvider pattern
 *     introduced by IG-7. Race-safe against concurrent creates that would otherwise
 *     bypass the applicative check.
 */
@Service
class AiModelServiceImpl(
    private val repository: AiModelRepository,
    private val aiProviderService: AiProviderService,
) : AiModelService {
    override fun create(entity: AiModel): AiModel {
        // Per-provider alias pre-check (level 1 above).
        entity.alias?.let { alias ->
            findByAiProviderAndAlias(entity.aiProviderId, alias)?.let {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    aliasConflictMessage(alias, entity.aiProviderId),
                )
            }
        }
        val parent = aiProviderService.getById(entity.aiProviderId)
        val denormalised = entity.copy(
            namespaceId = parent.namespaceId,
            userId = parent.userId,
        )
        return saveOrConflict(denormalised)
    }

    override fun update(entity: AiModel): AiModel {
        // Same per-provider alias rule as create.
        entity.alias?.let { alias ->
            findByAiProviderAndAlias(entity.aiProviderId, alias)
                ?.takeIf { it.id != entity.id }
                ?.let {
                    throw ResponseStatusException(
                        HttpStatus.CONFLICT,
                        aliasConflictMessage(alias, entity.aiProviderId),
                    )
                }
        }
        return saveOrConflict(entity)
    }

    override fun findByIds(ids: Collection<UUID>): List<AiModel> = repository.findByIds(ids)

    override fun findByParent(parentId: UUID): List<AiModel> = repository.findByParent(parentId)

    override fun delete(id: UUID): Boolean = repository.delete(id)

    override fun deleteByParent(parentId: UUID): Int = repository.deleteByParent(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AiModel> = repository.findByNamespaceId(namespaceId)

    override fun findByAiProviderAndApiName(
        aiProviderId: UUID,
        apiName: String,
    ): AiModel? = repository.findByAiProviderAndApiName(aiProviderId, apiName)

    override fun findByAiProviderAndAlias(
        aiProviderId: UUID,
        alias: String,
    ): AiModel? = repository.findByAiProviderAndAlias(aiProviderId, alias)

    override fun findByUserId(userId: UUID): List<AiModel> = repository.findByUserId(userId)

    override fun findAiModel(
        namespaceId: UUID,
        name: String,
    ): AiModel? {
        val candidates = repository.findByNamespaceId(namespaceId)
        return candidates
            .filter { it.alias.equals(name, ignoreCase = true) }
            .maxByOrNull { it.priority }
            ?: candidates
                .filter { it.apiModelName.equals(name, ignoreCase = true) }
                .maxByOrNull { it.priority }
    }

    private fun saveOrConflict(entity: AiModel): AiModel =
        try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Catches the race window: two concurrent creates pass the applicative pre-check,
            // both reach `save`, the DB unique constraint on `tripleKey` rejects one of them.
            // Translate to 409 only when the violation is identifiably the triple-uniqueness
            // constraint — any other integrity error is rethrown so it surfaces as 500.
            if (!isTripleKeyConflict(e)) {
                throw e
            }
            // Do NOT chain `e` to the logger — the Neo4j driver's exception message can echo
            // property values which is not desirable in logs (NFR-SEC-4).
            logger.warn {
                "[AiModelService] tripleKey unique-constraint violation on save " +
                    "(namespaceId=${entity.namespaceId}, userId=${entity.userId}, alias='${entity.alias}', apiName='${entity.apiModelName}')"
            }
            throw ResponseStatusException(HttpStatus.CONFLICT, tripleConflictMessage(entity), e)
        }

    private fun isTripleKeyConflict(e: DataIntegrityViolationException): Boolean {
        val haystack =
            generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(separator = " | ")
        return TRIPLE_KEY_CONSTRAINT_NAME in haystack || TRIPLE_KEY_PROPERTY in haystack
    }

    private fun aliasConflictMessage(
        alias: String,
        aiProviderId: UUID,
    ): String = "A model config with alias '$alias' already exists in AiProvider $aiProviderId"

    private fun tripleConflictMessage(entity: AiModel): String =
        "An AI model named '${entity.alias ?: entity.apiModelName}' already exists for this scope " +
            "(namespaceId=${entity.namespaceId}, userId=${entity.userId})"

    companion object : KLogging() {
        private const val TRIPLE_KEY_CONSTRAINT_NAME = "ai_model_triple_key_unique"
        private const val TRIPLE_KEY_PROPERTY = "tripleKey"
    }
}
