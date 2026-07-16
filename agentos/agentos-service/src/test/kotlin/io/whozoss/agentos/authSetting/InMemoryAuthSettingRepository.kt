package io.whozoss.agentos.authSetting

import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import java.util.UUID

/** Test-only in-memory implementation of [AuthSettingRepository]. */
class InMemoryAuthSettingRepository : AuthSettingRepository {
    private val delegate = InMemoryEntityRepository<AuthSetting, String>(
        parentIdExtractor = { ALL_KEY },
        comparator = compareBy { it.name },
    )

    override fun save(entity: AuthSetting): AuthSetting = delegate.save(entity)
    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<AuthSetting> = delegate.findByIds(ids, withRemoved)
    override fun findByParent(parentId: UUID): List<AuthSetting> = findByNamespaceId(parentId)
    override fun delete(id: UUID): Boolean = delegate.delete(id)
    override fun deleteByParent(parentId: UUID): Int =
        findByNamespaceId(parentId).count { delegate.delete(it.metadata.id) }

    override fun findByNamespaceId(namespaceId: UUID): List<AuthSetting> =
        delegate.findAll().filter { it.namespaceId == namespaceId && it.userId == null }

    override fun findByUserId(userId: UUID): List<AuthSetting> =
        delegate.findAll().filter { it.userId == userId }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AuthSetting? =
        delegate.findAll().firstOrNull {
            it.namespaceId == namespaceId && it.userId == userId && it.name == name
        }

    override fun findPlatformLevel(): List<AuthSetting> =
        delegate.findAll().filter { it.namespaceId == null && it.userId == null }

    override fun findAllForScope(
        namespaceId: UUID,
        userId: UUID,
    ): List<AuthSetting> =
        delegate.findAll().filter {
            (it.namespaceId == null || it.namespaceId == namespaceId) &&
                (it.userId == null || it.userId == userId)
        }

    companion object { private const val ALL_KEY = "all" }
}
