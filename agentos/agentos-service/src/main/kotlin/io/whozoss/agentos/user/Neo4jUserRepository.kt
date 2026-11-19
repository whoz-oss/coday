package io.whozoss.agentos.user

import io.whozoss.agentos.sdk.api.user.UsersListFilters
import io.whozoss.agentos.user.UserRepository.Companion.USER_PARENT_KEY
import mu.KLogging
import org.springframework.data.neo4j.core.Neo4jTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [UserRepository].
 *
 * Users are root-level entities. The parent type is [String] and the only
 * valid value is [USER_PARENT_KEY] = "all".
 * [findByParent] and [deleteByParent] ignore the actual value and operate on
 * all non-removed users.
 *
 * [findByExternalId] delegates to an indexed Cypher query rather than the
 * O(n) filesystem scan used by [io.whozoss.agentos.user.FilesystemUserRepository].
 */
open class Neo4jUserRepository(
    private val userNodeNeo4jRepository: UserNodeNeo4jRepository,
    private val neo4jTemplate: Neo4jTemplate,
) : UserRepository {
    override fun save(entity: User): User =
        userNodeNeo4jRepository
            .save(UserNode.fromDomain(entity))
            .also { if (!entity.metadata.removed) userNodeNeo4jRepository.setActive(it.id) }
            .toDomain()
            .also { logger.debug { "[Neo4jUserRepository] Saved user ${it.id} (${entity.email})" } }

    override fun findByIds(ids: Collection<UUID>, withRemoved: Boolean): List<User> =
        userNodeNeo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    /**
     * [parentId] is always [USER_PARENT_KEY] = "all".
     * Returns all non-removed users.
     */
    override fun findByParent(parentId: String): List<User> = userNodeNeo4jRepository.findAllActive().map { it.toDomain() }

    override fun findByExternalId(externalId: String): User? = userNodeNeo4jRepository.findActiveByExternalId(externalId)?.toDomain()

    override fun findByExternalIds(externalIds: Set<String>): List<User> =
        userNodeNeo4jRepository.findActiveByExternalIds(externalIds).map { it.toDomain() }

    override fun findPage(
        filters: UsersListFilters,
        afterExternalId: String?,
        limit: Int,
    ): List<User> {
        val (cypher, params) = usersPageCypher(filters, afterExternalId, limit)
        return neo4jTemplate.findAll(cypher, params, UserNode::class.java).map { it.toDomain() }
    }

    override fun countMatching(filters: UsersListFilters): Long {
        val (cypher, params) = usersCountCypher(filters)
        return neo4jTemplate.count(cypher, params)
    }

    override fun count(): Long = userNodeNeo4jRepository.countActive()

    override fun delete(id: UUID): Boolean =
        userNodeNeo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                userNodeNeo4jRepository.save(node.copy(removed = true))
                userNodeNeo4jRepository.setInactive(node.id)
                logger.debug { "[Neo4jUserRepository] Soft-deleted user $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: String): Int {
        val active = userNodeNeo4jRepository.findAllActive()
        userNodeNeo4jRepository.saveAll(active.map { it.copy(removed = true) })
        userNodeNeo4jRepository.setInactiveByIds(active.map { it.id })
        logger.debug { "[Neo4jUserRepository] Soft-deleted ${active.size} users" }
        return active.size
    }

    companion object : KLogging()
}

internal fun usersPageCypher(
    filters: UsersListFilters,
    afterExternalId: String?,
    limit: Int,
): Pair<String, Map<String, Any>> =
    usersScope(filters, afterExternalId).let { (scope, params) ->
        // A user holding both ADMIN and MEMBER links is reached twice on the namespace path, hence DISTINCT.
        val distinct = if (filters.namespaceId != null) "DISTINCT " else ""
        $$"$$scope RETURN $${distinct}u ORDER BY u.externalId ASC LIMIT $limit" to params + ("limit" to limit)
    }

private fun usersCountCypher(filters: UsersListFilters): Pair<String, Map<String, Any>> =
    usersScope(filters, null).let { (scope, params) -> "$scope RETURN count(DISTINCT u)" to params }

/**
 * The texts a users `query` is matched against: each field, plus the full name as displayed, in both orders, so
 * "jane smith" and "smith jane" find a user whose names are stored apart. A null name counts as empty, so
 * a one-name user has no stray space in the full name and a null never breaks the OR chain.
 */
private val SEARCHED_TEXTS =
    listOf(
        "u.email",
        "u.externalId",
        "u.firstname",
        "u.lastname",
        "trim(COALESCE(u.firstname, '') + ' ' + COALESCE(u.lastname, ''))",
        "trim(COALESCE(u.lastname, '') + ' ' + COALESCE(u.firstname, ''))",
    )

/**
 * The MATCH/WHERE selecting the listed users. Every caller-supplied value is a bound parameter and CONTAINS is
 * literal, so the filter text cannot change the statement. "Active" is the `removed` property, like every other
 * user and namespace read. `externalId IS NOT NULL` lets the planner walk the `:User(externalId)` index in order
 * instead of sorting every user; a namespace filter starts from the namespace's links instead of testing each user.
 */
private fun usersScope(
    filters: UsersListFilters,
    afterExternalId: String?,
): Pair<String, Map<String, Any>> {
    val params = mutableMapOf<String, Any>()
    val match =
        filters.namespaceId
            ?.let {
                params["namespaceId"] = it.toString()
                $$"MATCH (n:Namespace {id: $namespaceId}) WHERE NOT COALESCE(n.removed, false) MATCH (u:User)-[:ADMIN|MEMBER]->(n)"
            } ?: "MATCH (u:User)"
    val predicates = mutableListOf("u.externalId IS NOT NULL", "NOT COALESCE(u.removed, false)")
    afterExternalId?.let {
        predicates += $$"u.externalId > $afterExternalId"
        params["afterExternalId"] = it
    }
    filters.query?.takeIf { it.isNotBlank() }?.let {
        predicates += SEARCHED_TEXTS.joinToString(" OR ", "(", ")") { text -> $$"toLower($$text) CONTAINS toLower($query)" }
        params["query"] = it
    }
    return "$match WHERE ${predicates.joinToString(" AND ")}" to params
}
