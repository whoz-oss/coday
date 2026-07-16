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

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<UserGroup> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<UserGroup> =
        neo4jRepository
            .findActiveByNamespaceId(parentId.toString())
            .map { it.toDomain() }

    override fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult> =
        querySearchResults(
            whereClause = $$"ns.id = $namespaceId AND NOT COALESCE(g.removed, false) AND NOT COALESCE(ns.removed, false)",
            paramName = "namespaceId",
            paramValue = namespaceId.toString(),
        ).all().toList()

    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? =
        querySearchResults(
            whereClause = $$"g.id = $userGroupId AND NOT COALESCE(g.removed, false) AND NOT COALESCE(ns.removed, false)",
            paramName = "userGroupId",
            paramValue = id.toString(),
        ).one().orElse(null)

    override fun findMembers(userGroupId: UUID): List<UserGroupMember> =
        neo4jClient
            .query(
                $$"""
                    MATCH (u:User)-[r:MEMBER|ADMIN]->(g:UserGroup {id: $userGroupId})
                    WHERE NOT COALESCE(u.removed, false)
                    WITH u, collect(type(r)) AS rels
                    RETURN u.id AS userId, u.externalId AS externalId, u.email AS email,
                           u.firstname AS firstname, u.lastname AS lastname,
                           CASE WHEN 'ADMIN' IN rels THEN 'ADMIN' ELSE 'MEMBER' END AS role
                    ORDER BY u.externalId ASC
                """.trimIndent(),
            ).bind(userGroupId.toString())
            .to("userGroupId")
            .fetchAs(UserGroupMember::class.java)
            .mappedBy { _, record ->
                UserGroupMember(
                    userId = UUID.fromString(record["userId"].asString()),
                    externalId = record["externalId"].asString(),
                    role = record["role"].asString(),
                    email = record["email"].takeUnless { it.isNull }?.asString(),
                    firstname = record["firstname"].takeUnless { it.isNull }?.asString(),
                    lastname = record["lastname"].takeUnless { it.isNull }?.asString(),
                )
            }.all()
            .toList()

    private fun querySearchResults(
        whereClause: String,
        paramName: String,
        paramValue: String,
    ) = neo4jClient
        .query(
            """
                MATCH (g:UserGroup)-[:BELONGS_TO]->(ns:Namespace)
                WHERE $whereClause
                OPTIONAL MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(g)
                  WHERE NOT COALESCE(a.removed, false)
                OPTIONAL MATCH (u:User)-[:MEMBER|ADMIN]->(g)
                  WHERE NOT COALESCE(u.removed, false)
                RETURN g.id AS userGroupId, ns.id AS namespaceId, ns.externalId AS namespaceExternalId,
                       g.name AS name, collect(DISTINCT a.id) AS agentIds, count(DISTINCT u) AS userCount
                ORDER BY g.name ASC
            """.trimIndent(),
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

    override fun setMemberRoles(
        userGroupId: UUID,
        adminExternalIds: Collection<String>,
    ) {
        val groupId = userGroupId.toString()
        val admins = adminExternalIds.toList()
        neo4jRepository.promoteAdmins(groupId, admins)
        neo4jRepository.demoteNonAdmins(groupId, admins)
    }

    /**
     * Returns groups for the given user external IDs, optionally scoped to a namespace.
     *
     * When [namespaceId] is null, groups from all namespaces are returned.
     * When [namespaceId] is provided, the Cypher query adds an extra predicate
     * `AND g.namespaceId = $$namespaceId` to scope results to a single federation.
     */
    override fun findGroupsByUserExternalIds(
        externalIds: Collection<String>,
        namespaceId: UUID?,
    ): Map<String, List<UserGroupSummary>> {
        if (externalIds.isEmpty()) return emptyMap()
        val params = mutableMapOf<String, Any>("externalIds" to externalIds.toList())
        val namespaceClause: String
        if (namespaceId != null) {
            params["namespaceId"] = namespaceId.toString()
            namespaceClause = $$"AND g.namespaceId = $namespaceId"
        } else {
            namespaceClause = ""
        }
        val query = $$"""
            MATCH (u:User)-[:MEMBER]->(g:UserGroup)
            WHERE u.externalId IN $externalIds
              AND NOT COALESCE(g.removed, false)
              AND NOT COALESCE(u.removed, false)
              $$namespaceClause
            RETURN u.externalId AS externalId, g.id AS groupId, g.name AS groupName
            ORDER BY u.externalId ASC, g.name ASC
        """.trimIndent()
        return neo4jClient
            .query(query)
            .bindAll(params)
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
