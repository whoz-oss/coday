package io.whozoss.agentos.authSetting

import io.whozoss.agentos.encryption.FieldEncryptor
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [AuthSettingRepository].
 *
 * [findByParent] delegates to [findByNamespaceId] by convention -- namespace is the
 * primary scope. [findByUserId] is the primary path for user-scoped settings.
 *
 * Encryption is applied to the entire [AuthSettingNode.dataJson] string as a single unit
 * before writing, and decryption reverses that on read, right here around the
 * `neo4jRepository` calls. This mirrors [io.whozoss.agentos.credential.Neo4jCredentialRepository]
 * and keeps [AuthSettingNode.toDomain] / [AuthSettingNode.fromDomain] free of any encryption
 * concern -- they only ever see plaintext JSON.
 */
open class Neo4jAuthSettingRepository(
    private val neo4jRepository: AuthSettingNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
    private val encryptor: FieldEncryptor,
) : AuthSettingRepository {
    override fun save(entity: AuthSetting): AuthSetting {
        val node = AuthSettingNode.fromDomain(entity)
        val encrypted = node.copy(dataJson = encryptDataJson(node.dataJson))
        return neo4jRepository
            .save(encrypted)
            .also { savedNode ->
                // Only link namespace-scoped settings. User-scoped settings (userId != null,
                // namespaceId == null) skip this step -- they have no namespace to link to.
                entity.namespaceId?.let { nsId ->
                    childLinkService.link("AuthSetting", savedNode.id, "Namespace", nsId.toString())
                }
            }.let { it.copy(dataJson = decryptDataJson(it.dataJson)) }
            .toDomain()
            .also { logger.debug { "[Neo4jAuthSettingRepository] Saved AuthSetting ${it.id} ('${entity.name}')" } }
    }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<AuthSetting> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain() }

    // findByParent by convention delegates to findByNamespaceId
    override fun findByParent(parentId: UUID): List<AuthSetting> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AuthSetting> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain() }

    override fun findByUserId(userId: UUID): List<AuthSetting> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain() }

    override fun findByNamespaceIdAndUserIdAndName(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AuthSetting? =
        neo4jRepository
            .findActiveByTripleKey(AuthSettingNode.computeTripleKey(namespaceId, userId, name))
            ?.let { it.copy(dataJson = decryptDataJson(it.dataJson)) }
            ?.toDomain()

    override fun findPlatformLevel(): List<AuthSetting> =
        neo4jRepository
            .findActivePlatformLevel()
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain() }

    override fun findAllForScope(
        namespaceId: UUID,
        userId: UUID,
    ): List<AuthSetting> =
        neo4jRepository
            .findAllForNamespaceAndUser(namespaceId.toString(), userId.toString())
            .map { it.copy(dataJson = decryptDataJson(it.dataJson)).toDomain() }

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                // Tombstone the tripleKey at soft-delete so the unique slot is freed for
                // immediate re-creation of `(ns, user, name)`. Cf. RFC §D11.
                // dataJson is carried over unchanged (still encrypted) -- no need to touch it.
                neo4jRepository.save(
                    node.copy(
                        removed = true,
                        tripleKey = AuthSettingNode.tombstoneTripleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jAuthSettingRepository] Soft-deleted AuthSetting $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(
            active.map { it.copy(removed = true, tripleKey = AuthSettingNode.tombstoneTripleKey(it.id)) },
        )
        logger.debug { "[Neo4jAuthSettingRepository] Soft-deleted ${active.size} AuthSettings under namespace $parentId" }
        return active.size
    }

    private fun encryptDataJson(dataJson: String?): String? =
        dataJson?.let { encryptor.encrypt(it) }

    private fun decryptDataJson(dataJson: String?): String? =
        dataJson?.let { encryptor.decrypt(it) }

    companion object : KLogging()
}
