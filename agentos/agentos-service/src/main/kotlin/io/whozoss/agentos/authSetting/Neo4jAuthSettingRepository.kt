package io.whozoss.agentos.authSetting

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.encryption.FieldEncryptor
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
 * [encryptor] is injected and forwarded to [AuthSettingNode.toDomain] /
 * [AuthSettingNode.fromDomain] so that each value in [AuthSetting.data] is
 * individually encrypted at rest.
 */
open class Neo4jAuthSettingRepository(
    private val neo4jRepository: AuthSettingNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
    private val encryptor: FieldEncryptor,
) : AuthSettingRepository {
    override fun save(entity: AuthSetting): AuthSetting =
        neo4jRepository
            .save(AuthSettingNode.fromDomain(entity, encryptor))
            .also { savedNode ->
                // Only link namespace-scoped settings. User-scoped settings (userId != null,
                // namespaceId == null) skip this step -- they have no namespace to link to.
                entity.namespaceId?.let { nsId ->
                    childLinkService.link("AuthSetting", savedNode.id, "Namespace", nsId.toString())
                }
            }
            .toDomain(encryptor)
            .also { logger.debug { "[Neo4jAuthSettingRepository] Saved AuthSetting ${it.id} ('${entity.name}')" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<AuthSetting> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain(encryptor) }

    // findByParent by convention delegates to findByNamespaceId
    override fun findByParent(parentId: UUID): List<AuthSetting> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<AuthSetting> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain(encryptor) }

    override fun findByUserId(userId: UUID): List<AuthSetting> =
        neo4jRepository
            .findActiveByUserId(userId.toString())
            .map { it.toDomain(encryptor) }

    override fun findByTriple(
        namespaceId: UUID?,
        userId: UUID?,
        name: String,
    ): AuthSetting? =
        neo4jRepository
            .findActiveByTripleKey(AuthSettingNode.computeTripleKey(namespaceId, userId, name))
            ?.toDomain(encryptor)

    override fun findPlatformLevel(): List<AuthSetting> =
        neo4jRepository
            .findActivePlatformLevel()
            .map { it.toDomain(encryptor) }

    override fun findAllForScope(
        namespaceId: UUID,
        userId: UUID,
    ): List<AuthSetting> =
        neo4jRepository
            .findAllForNamespaceAndUser(namespaceId.toString(), userId.toString())
            .map { it.toDomain(encryptor) }

    @Transactional
    open override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                // Tombstone the tripleKey at soft-delete so the unique slot is freed for
                // immediate re-creation of `(ns, user, name)`. Cf. RFC §D11.
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

    companion object : KLogging()
}
