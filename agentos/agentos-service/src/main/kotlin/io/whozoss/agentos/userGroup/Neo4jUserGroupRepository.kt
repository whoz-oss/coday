package io.whozoss.agentos.userGroup

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.*

private data class UserExternalIdGroupRow(
    val externalId: String,
    val groupId: String,
    val groupName: String,
)

open class Neo4jUserGroupRepository(
    private val neo4jRepository: UserGroupNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
    private val neo4jClient: Neo4jClient,
) : UserGroupRepository {
    override fun save(entity: UserGroup): UserGroup =
        neo4jRepository
            .save(UserGroupNode.fromDomain(entity))
            .also {
                childLinkService.link("UserGroup", it.id, "Namespace", entity.namespaceId.toString())
                if (!entity.metadata.removed) neo4jRepository.setActive(it.id)
            }.toDomain()

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
        querySearchResults(
            whereClause = $$"ns.externalId = $externalId AND (g.removed IS NULL OR g.removed = false) AND (ns.removed IS NULL OR ns.removed = false)",
            paramName = "externalId",
            paramValue = externalId,
        ).all().toList()

    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? =
        querySearchResults(
            whereClause = $$"g.id = $userGroupId AND (g.removed IS NULL OR g.removed = false) AND (ns.removed IS NULL OR ns.removed = false)",
            paramName = "userGroupId",
            paramValue = id.toString(),
        ).one().orElse(null)

    private fun querySearchResults(
        whereClause: String,
        paramName: String,
        paramValue: String,
    ) = neo4jClient
        .query(
            """
                MATCH (g:UserGroup)-[:BELONGS_TO]->(ns:Namespace)
                WHERE $whereClause
                OPTIONAL MATCH (g)-[:HAS_AGENT]->(a:AgentConfig)
                  WHERE a.removed IS NULL OR a.removed = false
                OPTIONAL MATCH (g)-[:HAS_USER]->(u:User)
                  WHERE u.removed IS NULL OR u.removed = false
                RETURN g.id AS userGroupId, ns.id AS namespaceId, ns.externalId AS namespaceExternalId, g.name AS name, collect(DISTINCT a.id) AS agentIds, count(DISTINCT u) AS userCount
                ORDER BY g.name ASC
            """,
        ).bind(paramValue)
        .to(paramName)
        .fetchAs(UserGroupSearchResult::class.java)
        .mappedBy { _, record ->
            UserGroupSearchResult(
                userGroupId = UUID.fromString(record["userGroupId"].asString()),
                namespaceId = UUID.fromString(record["namespaceId"].asString()),
                namespaceExternalId = record["namespaceExternalId"].asString(),
                name = record["name"].asString(),
                agentIds = record["agentIds"].asList { UUID.fromString(it.asString()) },
                userCount = record["userCount"].asInt(),
            )
        }

    override fun addAgents(
        userGroupId: UUID,
        agentConfigIds: Collection<UUID>,
    ) {
        neo4jRepository.addAgents(userGroupId.toString(), agentConfigIds.map { it.toString() })
    }

    override fun removeAllAgents(userGroupId: UUID) {
        neo4jRepository.removeAllAgents(userGroupId.toString())
    }

    override fun addUsers(
        userGroupId: UUID,
        userExternalIds: Collection<String>,
    ) {
        neo4jRepository.addUsers(userGroupId.toString(), userExternalIds.toList())
    }

    override fun removeUsers(
        userGroupId: UUID,
        userExternalIds: Collection<String>,
    ) {
        neo4jRepository.removeUsers(userGroupId.toString(), userExternalIds.toList())
    }

    override fun findGroupsByUserExternalIds(externalIds: Collection<String>): Map<String, List<UserGroupSummary>> {
        if (externalIds.isEmpty()) return emptyMap()
        return neo4jClient
            .query(
                """
                    UNWIND $externalIds AS extId
                    MATCH (g:UserGroup)-[:HAS_USER]->(u:User {externalId: extId})
                    WHERE (g.removed IS NULL OR g.removed = false)
                      AND (u.removed IS NULL OR u.removed = false)
                    RETURN extId AS externalId, g.id AS groupId, g.name AS groupName
                    ORDER BY extId ASC, g.name ASC
                """,
            ).bind(externalIds.toList())
            .to("externalIds")
            .fetchAs(UserExternalIdGroupRow::class.java)
            .mappedBy { _, record ->
                UserExternalIdGroupRow(
                    externalId = record["externalId"].asString(),
                    groupId = record["groupId"].asString(),
                    groupName = record["groupName"].asString(),
                )
            }.all()
            .groupBy(
                keySelector = { it.externalId },
                valueTransform = { UserGroupSummary(id = UUID.fromString(it.groupId), name = it.groupName) },
            )
    }

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(node.copy(removed = true))
                neo4jRepository.setInactive(node.id)
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.setInactiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(active.map { it.copy(removed = true) })
        return active.size
    }

    companion object : KLogging()
}
