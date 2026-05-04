package io.whozoss.agentos.usergroup

import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

open class Neo4jUserGroupRepository(
    private val neo4jRepository: UserGroupNodeNeo4jRepository,
) : UserGroupRepository {
    override fun save(entity: UserGroup): UserGroup =
        neo4jRepository
            .save(UserGroupNode.fromDomain(entity))
            .toDomain()
            .also { logger.debug { "[Neo4jUserGroupRepository] Saved user group ${it.id} (${entity.name})" } }

    override fun findByIds(ids: Collection<UUID>): List<UserGroup> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<UserGroup> =
        neo4jRepository.findActiveByNamespaceId(parentId.toString()).map { it.toDomain() }

    override fun findAgentIds(userGroupId: UUID): List<UUID> =
        neo4jRepository.findAgentIdsByUserGroupId(userGroupId.toString()).map { UUID.fromString(it) }

    override fun countUsers(userGroupId: UUID): Int =
        neo4jRepository.countActiveUsers(userGroupId.toString())

    override fun countAgents(userGroupId: UUID): Int =
        neo4jRepository.countActiveAgents(userGroupId.toString())

    override fun addUser(userGroupId: UUID, userId: UUID) =
        neo4jRepository.addUser(userGroupId.toString(), userId.toString())

    override fun removeUser(userGroupId: UUID, userId: UUID) =
        neo4jRepository.removeUser(userGroupId.toString(), userId.toString())

    @Transactional
    open override fun replaceAgents(userGroupId: UUID, agentIds: Set<UUID>) {
        neo4jRepository.removeAllAgents(userGroupId.toString())
        agentIds.forEach { agentId ->
            neo4jRepository.addAgent(userGroupId.toString(), agentId.toString())
        }
    }

    @Transactional
    open override fun softDeleteWithRelationships(userGroupId: UUID) {
        neo4jRepository.softDeleteWithRelationships(userGroupId.toString())
        logger.debug { "[Neo4jUserGroupRepository] Soft-deleted user group $userGroupId with relationships" }
    }

    override fun delete(id: UUID): Boolean {
        val node = neo4jRepository.findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?: return false
        softDeleteWithRelationships(id)
        return true
    }

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        active.forEach { softDeleteWithRelationships(UUID.fromString(it.id)) }
        logger.debug { "[Neo4jUserGroupRepository] Soft-deleted ${active.size} user groups under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
