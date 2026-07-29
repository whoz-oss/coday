package io.whozoss.agentos.credential

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.encryption.FieldEncryptor
import mu.KLogging
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Neo4j-backed implementation of [CredentialRepository].
 *
 * Encryption is applied to the entire [CredentialNode.dataJson] string as a single unit
 * before writing, and decryption reverses that on read. This avoids per-field
 * parse/encrypt/re-serialize overhead and produces a single IV+tag per credential.
 *
 * [deleteByUserAndAuthSetting] and [deleteByAuthSettingId] are soft-deletes:
 * they set `removed = true` on the node rather than physically removing it.
 */
open class Neo4jCredentialRepository(
    private val neo4jRepository: CredentialNodeNeo4jRepository,
    private val encryptor: FieldEncryptor,
    private val objectMapper: ObjectMapper,
) : CredentialRepository {
    override fun save(credential: Credential): Credential {
        val node = CredentialNode.fromDomain(credential, objectMapper)
        val encrypted = node.copy(dataJson = encryptDataJson(node.dataJson))
        return neo4jRepository
            .save(encrypted)
            .let { it.copy(dataJson = decryptDataJson(it.dataJson)) }
            .toDomain(objectMapper)
            .also { logger.debug { "[Neo4jCredentialRepository] Saved Credential ${it.id} (user=${credential.userId}, authSetting=${credential.authSettingId})" } }
    }

    override fun findByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Credential? =
        neo4jRepository
            .findActiveByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString())
            ?.let { it.copy(dataJson = decryptDataJson(it.dataJson)) }
            ?.toDomain(objectMapper)

    @Transactional
    override fun deleteByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Boolean =
        neo4jRepository
            .findActiveByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString())
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true, modified = Instant.now(), modifiedBy = userId.toString()))
                logger.debug { "[Neo4jCredentialRepository] Soft-deleted Credential ${node.id} (user=$userId, authSetting=$authSettingId)" }
                true
            } ?: false

    @Transactional
    override fun deleteByAuthSettingId(authSettingId: UUID): Int {
        val active = neo4jRepository.findActiveByAuthSettingId(authSettingId.toString())
        val now = Instant.now()
        neo4jRepository.saveAll(active.map { it.copy(removed = true, modified = now) })
        logger.debug { "[Neo4jCredentialRepository] Cascade soft-deleted ${active.size} Credential(s) for authSetting=$authSettingId" }
        return active.size
    }

    override fun findByUserId(userId: UUID): List<Credential> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain(objectMapper) }

    private fun encryptDataJson(dataJson: String?): String? =
        dataJson?.let { encryptor.encrypt(it) }

    private fun decryptDataJson(dataJson: String?): String? =
        dataJson?.let { encryptor.decrypt(it) }

    companion object : KLogging()
}
