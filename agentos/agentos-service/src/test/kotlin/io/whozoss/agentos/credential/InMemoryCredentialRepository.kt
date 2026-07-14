package io.whozoss.agentos.credential

import io.whozoss.agentos.sdk.credential.Credential
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Test-only in-memory implementation of [CredentialRepository]. */
class InMemoryCredentialRepository : CredentialRepository {
    private val store = ConcurrentHashMap<UUID, Credential>()

    override fun save(credential: Credential): Credential {
        // Upsert: remove any existing active credential for the same (userId, authSettingId)
        // before inserting, so the pair always maps to exactly one active credential.
        store.values
            .firstOrNull { !it.metadata.removed && it.userId == credential.userId && it.authSettingId == credential.authSettingId && it.id != credential.id }
            ?.let { store[it.id] = it.copy(metadata = it.metadata.markAsRemoved()) }
        store[credential.id] = credential
        return credential
    }

    override fun findByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Credential? =
        store.values.firstOrNull {
            !it.metadata.removed && it.userId == userId && it.authSettingId == authSettingId
        }

    override fun deleteByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Boolean {
        val credential = store.values.firstOrNull {
            !it.metadata.removed && it.userId == userId && it.authSettingId == authSettingId
        } ?: return false
        store[credential.id] = credential.copy(metadata = credential.metadata.markAsRemoved())
        return true
    }

    override fun deleteByAuthSettingId(authSettingId: UUID): Int {
        val active = store.values.filter { !it.metadata.removed && it.authSettingId == authSettingId }
        active.forEach { store[it.id] = it.copy(metadata = it.metadata.markAsRemoved()) }
        return active.size
    }

    override fun findByUserId(userId: UUID): List<Credential> =
        store.values.filter { !it.metadata.removed && it.userId == userId }
}
