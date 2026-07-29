package io.whozoss.agentos.credential

import io.whozoss.agentos.sdk.credential.Credential
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Test-only in-memory implementation of [CredentialRepository]. */
// TODO: Replace with embedded Neo4j test setup to eliminate in-memory test doubles
class InMemoryCredentialRepository : CredentialRepository {
    private val store = ConcurrentHashMap<UUID, Credential>()

    override fun save(credential: Credential): Credential {
        // Upsert: remove any existing credential for the same (userId, authSettingId)
        // before inserting, so the pair always maps to exactly one credential.
        store.values
            .firstOrNull { it.userId == credential.userId && it.authSettingId == credential.authSettingId && it.id != credential.id }
            ?.let { store.remove(it.id) }
        store[credential.id] = credential
        return credential
    }

    override fun findByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Credential? =
        store.values.firstOrNull { it.userId == userId && it.authSettingId == authSettingId }

    override fun deleteByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Boolean {
        val credential = store.values.firstOrNull {
            it.userId == userId && it.authSettingId == authSettingId
        } ?: return false
        store.remove(credential.id)
        return true
    }

    override fun deleteByAuthSettingId(authSettingId: UUID): Int {
        val toDelete = store.values.filter { it.authSettingId == authSettingId }
        toDelete.forEach { store.remove(it.id) }
        return toDelete.size
    }

    override fun findByUserId(userId: UUID): List<Credential> =
        store.values.filter { it.userId == userId }
}
