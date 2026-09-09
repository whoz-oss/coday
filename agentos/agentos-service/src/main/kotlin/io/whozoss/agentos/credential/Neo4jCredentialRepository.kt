package io.whozoss.agentos.credential

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.encryption.FieldEncryptor
import mu.KLogging
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [CredentialRepository].
 *
 * Encryption is applied to the entire [CredentialNode.dataJson] string as a single unit
 * before writing, and decryption reverses that on read. This avoids per-field
 * parse/encrypt/re-serialize overhead and produces a single IV+tag per credential.
 *
 * Deletes are **hard-deletes**: nodes are physically removed from the graph.
 * Sensitive credential material must not linger after revocation.
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
            .upsert(
                id = encrypted.id,
                userId = encrypted.userId,
                authSettingId = encrypted.authSettingId,
                credentialType = encrypted.credentialType,
                dataJson = encrypted.dataJson,
                created = encrypted.created,
                createdBy = encrypted.createdBy,
                modified = encrypted.modified,
                modifiedBy = encrypted.modifiedBy,
            )
            .let { it.copy(dataJson = decryptDataJson(it.dataJson)) }
            .toDomain(objectMapper)
            .also { logger.debug { "[Neo4jCredentialRepository] Saved Credential ${it.id} (user=${credential.userId}, authSetting=${credential.authSettingId})" } }
    }

    override fun findByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Credential? =
        neo4jRepository
            .findByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString())
            ?.let { it.copy(dataJson = decryptDataJson(it.dataJson)) }
            ?.toDomain(objectMapper)

    @Transactional
    override fun deleteByUserAndAuthSetting(userId: UUID, authSettingId: UUID): Boolean {
        val exists = neo4jRepository.findByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString()) != null
        if (exists) {
            neo4jRepository.deleteByUserIdAndAuthSettingId(userId.toString(), authSettingId.toString())
            logger.debug { "[Neo4jCredentialRepository] Hard-deleted Credential (user=$userId, authSetting=$authSettingId)" }
        }
        return exists
    }

    @Transactional
    override fun deleteByAuthSettingId(authSettingId: UUID): Int {
        val count = neo4jRepository.findByAuthSettingId(authSettingId.toString()).size
        neo4jRepository.deleteByAuthSettingId(authSettingId.toString())
        logger.debug { "[Neo4jCredentialRepository] Cascade hard-deleted $count Credential(s) for authSetting=$authSettingId" }
        return count
    }

    override fun findByUserId(userId: UUID): List<Credential> =
        neo4jRepository
            .findByUserId(userId.toString())
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain(objectMapper) }

    private fun encryptDataJson(dataJson: String?): String? =
        dataJson?.let { encryptor.encrypt(it) }

    private fun decryptDataJson(dataJson: String?): String? =
        dataJson?.let { encryptor.decrypt(it) }

    companion object : KLogging()
}
