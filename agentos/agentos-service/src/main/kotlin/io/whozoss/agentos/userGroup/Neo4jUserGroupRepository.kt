package io.whozoss.agentos.userGroup

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class Neo4jUserGroupRepository(
    private val neo4jRepository: UserGroupNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : UserGroupRepository {
    override fun save(entity: UserGroup): UserGroup =
        neo4jRepository
            .save(UserGroupNode.fromDomain(entity))
            .also { childLinkService.link("UserGroup", it.id, "Namespace", entity.namespaceId.toString()) }
            .toDomain()
            .also {
                logger.debug {
                    "[Neo4jUserGroupRepository] Saved user group ${it.id} ('${entity.name}') under namespace ${entity.namespaceId}"
                }
            }

    override fun findByIds(ids: Collection<UUID>): List<UserGroup> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<UserGroup> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun findByNamespaceExternalId(externalId: String): List<UserGroupSearchResult> =
        neo4jRepository
            .findByNamespaceExternalId(externalId)
            .map { it.toSearchResult() }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                logger.debug { "[Neo4jUserGroupRepository] Soft-deleted user group $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        logger.debug { "[Neo4jUserGroupRepository] Soft-deleted ${active.size} user groups under namespace $parentId" }
        return active.size
    }

    private fun UserGroupNamespaceProjection.toSearchResult() =
        UserGroupSearchResult(
            userGroupId = UUID.fromString(getUserGroup().id),
            namespaceId = UUID.fromString(getUserGroup().namespaceId),
            namespaceExternalId = getNamespaceExternalId(),
            name = getUserGroup().name,
        )

    companion object : KLogging()
}
