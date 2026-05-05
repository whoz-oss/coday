package io.whozoss.agentos.userGroup

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.*

open class Neo4jUserGroupRepository(
    private val neo4jRepository: UserGroupNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
    private val neo4jClient: Neo4jClient,
) : UserGroupRepository {
    override fun save(entity: UserGroup): UserGroup =
        neo4jRepository
            .save(UserGroupNode.fromDomain(entity))
            .also { childLinkService.link("UserGroup", it.id, "Namespace", entity.namespaceId.toString()) }
            .toDomain()

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
        neo4jClient
            .query(
                $$"""
                    MATCH (g:UserGroup)-[:BELONGS_TO]->(ns:Namespace)
                    WHERE ns.externalId = $externalId
                      AND (g.removed IS NULL OR g.removed = false)
                      AND (ns.removed IS NULL OR ns.removed = false)
                    OPTIONAL MATCH (g)-[:HAS_AGENT]->(a:AgentConfig)
                      WHERE a.removed IS NULL OR a.removed = false
                    RETURN g.id AS userGroupId, ns.id AS namespaceId, ns.externalId AS namespaceExternalId, g.name AS name, collect(a.id) AS agentIds
                    ORDER BY g.name ASC
                """,
            ).bind(externalId)
            .to("externalId")
            .fetchAs(UserGroupSearchResult::class.java)
            .mappedBy { _, record ->
                UserGroupSearchResult(
                    userGroupId = UUID.fromString(record["userGroupId"].asString()),
                    namespaceId = UUID.fromString(record["namespaceId"].asString()),
                    namespaceExternalId = record["namespaceExternalId"].asString(),
                    name = record["name"].asString(),
                    agentIds = record["agentIds"].asList { UUID.fromString(it.asString()) },
                )
            }.all()
            .toList()

    override fun addAgents(
        userGroupId: UUID,
        agentConfigIds: Collection<UUID>,
    ) {
        neo4jRepository.addAgents(userGroupId.toString(), agentConfigIds.map { it.toString() })
    }

    override fun removeAllAgents(userGroupId: UUID) {
        neo4jRepository.removeAllAgents(userGroupId.toString())
    }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        return active.size
    }

    companion object : KLogging()
}
