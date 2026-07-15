package io.whozoss.agentos.credential

import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.encryption.FieldEncryptor
import mu.KLogging
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CredentialRepository].
 *
 * [encryptor] is injected and forwarded to [CredentialNode.toDomain] /
 * [CredentialNode.fromDomain] so that each value in [Credential.data] is
 * individually encrypted at rest.
 *
 * [deleteByUserAndAuthSetting] and [deleteByAuthSettingId] are soft-deletes:
 * they set `removed = true` on the node rather than physically removing it.
 */
open class Neo4jCredentialRepository(
    private val neo4jRepository: CredentialNodeNeo4jRepository,
    private val encryptor: FieldEncryptor,
) : CredentialRepository {
    override fun save(credential: Credential): Credential =
        neo4jRepository
            .save(CredentialNode.fromDomain(credential, encryptor))
            .toDomain(encryptor)
            .also { logger.debug { "[Neo4jCredentialRepository] Saved Credential ${it.id} (user=${credential.userId}, authSetting=${credential.authSettingId})" } }

    override fun findByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Credential? =
        neo4jRepository
            .findActiveByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString())
            ?.toDomain(encryptor)

    @Transactional
    open override fun deleteByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Boolean =
        neo4jRepository
            .findActiveByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString())
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jCredentialRepository] Soft-deleted Credential ${node.id} (user=$userId, authSetting=$authSettingId)" }
                true
            } ?: false

    @Transactional
    open override fun deleteByAuthSettingId(authSettingId: UUID): Int {
        val active = neo4jRepository.findActiveByAuthSettingId(authSettingId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jCredentialRepository] Cascade soft-deleted ${active.size} Credential(s) for authSetting=$authSettingId" }
        return active.size
    }

    override fun findByUserId(userId: UUID): List<Credential> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain(encryptor) }

    companion object : KLogging()
}
