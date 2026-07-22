package io.whozoss.agentos.credential

import io.whozoss.agentos.sdk.credential.Credential
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Default implementation of [CredentialService].
 *
 * Delegates entirely to [CredentialRepository]. Business logic is intentionally
 * minimal — credentials are transactional data with no overlay or merge semantics.
 *
 * [store] has upsert semantics: it always calls [CredentialRepository.save], which
 * replaces any existing credential for the same `(userId, authSettingId)` pair.
 */
@Service
class CredentialServiceImpl(
    private val repository: CredentialRepository,
) : CredentialService {
    override fun store(credential: Credential): Credential {
        val saved = repository.save(credential)
        logger.debug { "[CredentialService] Stored credential ${saved.id} for user=${credential.userId}, authSetting=${credential.authSettingId}" }
        return saved
    }

    override fun resolve(userId: UUID, authSettingId: UUID): Credential? =
        repository.findByUserAndAuthSetting(userId, authSettingId)

    override fun revoke(userId: UUID, authSettingId: UUID): Boolean {
        val deleted = repository.deleteByUserAndAuthSetting(userId, authSettingId)
        if (deleted) {
            logger.debug { "[CredentialService] Revoked credential for user=$userId, authSetting=$authSettingId" }
        }
        return deleted
    }

    override fun revokeByAuthSetting(authSettingId: UUID): Int {
        val count = repository.deleteByAuthSettingId(authSettingId)
        logger.debug { "[CredentialService] Cascade-revoked $count credential(s) for authSetting=$authSettingId" }
        return count
    }

    override fun findByUserId(userId: UUID): List<Credential> =
        repository.findByUserId(userId)

    companion object : KLogging()
}
