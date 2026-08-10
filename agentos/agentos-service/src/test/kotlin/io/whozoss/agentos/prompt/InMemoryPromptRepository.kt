package io.whozoss.agentos.prompt

import io.whozoss.agentos.entity.InMemoryEntityRepository
import java.util.UUID

/**
 * Used by [PromptServiceImplSpec] to exercise business rules (parameter name uniqueness,
 * scope isolation, soft-delete) without a Neo4j dependency.
 *
 * [save] assigns `metadata.version = 0` on first persist when the incoming entity carries
 * `version == null`, mirroring what Spring Data Neo4j does on initial insert (see
 * [io.whozoss.agentos.sdk.entity.EntityMetadata] KDoc). This matters for
 * [PromptServiceImpl.update] / [PromptServiceImpl.delete], which reject any target whose
 * persisted copy still has `version == null` — that marker identifies a filesystem-backed
 * prompt (built in-memory by [FilesystemPromptRepository], never saved through SDN). Without
 * this simulation, every prompt created through this fixture would incorrectly look
 * filesystem-only and all update/delete tests would fail.
 */
class InMemoryPromptRepository : PromptRepository {
    private val delegate =
        InMemoryEntityRepository<Prompt, String>(
            parentIdExtractor = { ALL_KEY },
            comparator = compareBy { it.name },
        )

    override fun save(entity: Prompt): Prompt {
        val versioned = if (entity.metadata.version == null) {
            entity.copy(metadata = entity.metadata.copy(version = 0L))
        } else {
            entity
        }
        return delegate.save(versioned)
    }

    /**
     * Seeds [entity] bypassing the version-assignment logic in [save] — used by tests that
     * need to simulate a filesystem-backed prompt (`metadata.version == null` after "persist"),
     * e.g. to exercise [PromptServiceImpl]'s update/delete rejection.
     */
    fun seedRaw(entity: Prompt): Prompt = delegate.save(entity)

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Prompt> = delegate.findByIds(ids, withRemoved)

    override fun findByParent(parentId: UUID): List<Prompt> =
        delegate.findAll().filter { it.namespaceId == parentId && it.userId == null }

    override fun delete(id: UUID): Boolean = delegate.delete(id)

    override fun deleteByParent(parentId: UUID): Int =
        findByParent(parentId).count { delegate.delete(it.metadata.id) }

    override fun findPlatform(): List<Prompt> =
        delegate.findAll().filter { it.namespaceId == null && it.userId == null }

    override fun findByUserId(userId: UUID): List<Prompt> =
        delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(namespaceId: UUID?, userId: UUID?, name: String): Prompt? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    override fun findEffective(namespaceId: UUID, userId: UUID): List<Prompt> =
        delegate.findAll().filter { p ->
            (p.namespaceId == null && p.userId == null) ||
                (p.userId == userId && p.namespaceId == null) ||
                (p.namespaceId == namespaceId && p.userId == null) ||
                (p.namespaceId == namespaceId && p.userId == userId)
        }

    override fun findByScope(
        namespaceId: UUID?,
        userId: UUID?,
        agentConfigIds: List<UUID>?,
    ): List<Prompt> =
        delegate.findAll().filter { p ->
            p.namespaceId == namespaceId &&
                p.userId == userId &&
                (agentConfigIds.isNullOrEmpty() || p.agentConfigId in agentConfigIds)
        }

    override fun softDeleteByAgentConfigId(agentConfigId: UUID) {
        delegate.findAll()
            .filter { it.agentConfigId == agentConfigId }
            .forEach { delegate.delete(it.metadata.id) }
    }

    companion object {
        private const val ALL_KEY = "all"
    }
}
