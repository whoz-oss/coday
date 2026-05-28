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

private data class UserGroupMemberRow(
    val userId: String,
    val externalId: String,
    val email: String,
    val role: String,
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

    /**
     * List query — returns count only, members list is empty.
     * Loading full member lists for every group in a namespace listing is not acceptable
     * at scale (thousands of users per group).
     */
    override fun findByNamespaceId(namespaceId: UUID): List<UserGroupSearchResult> =
        neo4jClient
            .query(
                """
                    MATCH (g:UserGroup)-[:BELONGS_TO]->(ns:Namespace)
                    WHERE ns.id = ${'$'}namespaceId
                      AND NOT COALESCE(g.removed, false)
                      AND NOT COALESCE(ns.removed, false)
                    OPTIONAL MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(g)
                      WHERE NOT COALESCE(a.removed, false)
                    OPTIONAL MATCH (u:User)-[:ADMIN|MEMBER]->(g)
                      WHERE NOT COALESCE(u.removed, false)
                    RETURN g.id AS userGroupId, ns.id AS namespaceId, ns.externalId AS namespaceExternalId,
                           g.name AS name, collect(DISTINCT a.id) AS agentIds, count(DISTINCT u) AS userCount
                    ORDER BY g.name ASC
                """,
            ).bindAll(mapOf("namespaceId" to namespaceId.toString()))
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
            }.all().toList()

    /**
     * Detail query — returns the full member list with roles alongside agents.
     *
     * Two separate Cypher round-trips:
     * 1. Group metadata + agent ids + member count (same shape as the list query).
     * 2. Member list with roles (ADMIN or MEMBER) via `type(r)`.
     *
     * A single query cannot simultaneously aggregate agent ids and return per-member
     * role data without a cartesian explosion or complex WITH/UNWIND gymnastics.
     * Two simple queries are cleaner and each runs in O(members + agents).
     */
    override fun findByIdWithDetails(id: UUID): UserGroupSearchResult? {
        val base = neo4jClient
            .query(
                """
                    MATCH (g:UserGroup)-[:BELONGS_TO]->(ns:Namespace)
                    WHERE g.id = ${'$'}userGroupId
                      AND NOT COALESCE(g.removed, false)
                      AND NOT COALESCE(ns.removed, false)
                    OPTIONAL MATCH (a:AgentConfig)-[:DEPLOYED_TO]->(g)
                      WHERE NOT COALESCE(a.removed, false)
                    OPTIONAL MATCH (u:User)-[:ADMIN|MEMBER]->(g)
                      WHERE NOT COALESCE(u.removed, false)
                    RETURN g.id AS userGroupId, ns.id AS namespaceId, ns.externalId AS namespaceExternalId,
                           g.name AS name, collect(DISTINCT a.id) AS agentIds, count(DISTINCT u) AS userCount
                """,
            ).bindAll(mapOf("userGroupId" to id.toString()))
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
            }.one().orElse(null) ?: return null

        val members = neo4jClient
            .query(
                """
                    MATCH (u:User)-[r:ADMIN|MEMBER]->(g:UserGroup {id: ${'$'}userGroupId})
                    WHERE NOT COALESCE(u.removed, false)
                      AND NOT COALESCE(g.removed, false)
                    RETURN u.id AS userId, u.externalId AS externalId, u.email AS email,
                           type(r) AS role
                    ORDER BY u.email ASC
                """,
            ).bindAll(mapOf("userGroupId" to id.toString()))
            .fetchAs(UserGroupMemberRow::class.java)
            .mappedBy { _, record ->
                UserGroupMemberRow(
                    userId = record["userId"].asString(),
                    externalId = record["externalId"].asString(),
                    email = record["email"].asString(),
                    role = record["role"].asString(),
                )
            }.all().toList()

        return base.copy(
            members = members.map {
                UserGroupMember(
                    userId = UUID.fromString(it.userId),
                    externalId = it.externalId,
                    email = it.email,
                    role = it.role,
                )
            },
            userCount = members.size,
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

    override fun updateMemberships(userGroupId: UUID, entries: List<Pair<UUID, String?>>) {
        entries.forEach { (userId, role) ->
            neo4jRepository.upsertMembership(
                groupId = userGroupId.toString(),
                userId = userId.toString(),
                role = role,
            )
        }
    }

    override fun findGroupsByUserExternalIds(externalIds: Collection<String>): Map<String, List<UserGroupSummary>> {
        if (externalIds.isEmpty()) return emptyMap()
        return neo4jClient
            .query(
                $$"""
                    MATCH (u:User)-[:ADMIN|MEMBER]->(g:UserGroup)
                    WHERE u.externalId IN $externalIds
                      AND NOT COALESCE(g.removed, false)
                      AND NOT COALESCE(u.removed, false)
                    RETURN u.externalId AS externalId, g.id AS groupId, g.name AS groupName
                    ORDER BY u.externalId ASC, g.name ASC
                """.trimIndent(),
            ).bindAll(mapOf("externalIds" to externalIds.toList()))
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
