package io.whozoss.agentos.permissions

import io.whozoss.agentos.user.UserNode
import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.data.repository.query.Param

/**
 * Spring Data Neo4j repository for permission operations.
 * Uses @Query annotations to define Cypher queries following the correct pattern.
 *
 * This repository handles:
 * - Direct permission relationships between users and entities
 * - Transitive permission evaluation through namespace hierarchy
 * - Permission listing and management
 *
 * Note: Neo4j does not support parameterized relationship types or label comparisons
 * via parameters, so we use separate methods per relationship type and IN-based label checks.
 */
interface PermissionNodeNeo4jRepository : Neo4jRepository<UserNode, String> {
    // Direct permission queries

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN type(r) AS relation
    """,
    )
    fun findDirectPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): String?

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN COUNT(r) > 0
    """,
    )
    fun hasAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Boolean

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN COUNT(r) > 0
    """,
    )
    fun hasMemberOrAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Boolean

    // Transitive permission queries for namespace hierarchy

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[:ADMIN]->(n:Namespace)
        MATCH (n)<-[:BELONGS_TO]-(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN COUNT(e) > 0
    """,
    )
    fun hasAdminAccessViaNamespace(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Boolean

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[:ADMIN|MEMBER]->(n:Namespace)
        MATCH (n)<-[:BELONGS_TO]-(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN COUNT(e) > 0
    """,
    )
    fun hasReadAccessViaNamespace(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Boolean

    // Permission management queries — separate methods per relationship type
    // because Neo4j does not support parameterized relationship types.

    @Query(
        $$"""
        MATCH (u:User {id: $userId})
        MATCH (e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        MERGE (u)-[r:ADMIN]->(e)
        RETURN r
    """,
    )
    fun createAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    )

    @Query(
        $$"""
        MATCH (u:User {id: $userId})
        MATCH (e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        MERGE (u)-[r:MEMBER]->(e)
        RETURN r
    """,
    )
    fun createMemberPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    )

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        DELETE r
    """,
    )
    fun deleteAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    )

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        DELETE r
    """,
    )
    fun deleteMemberPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    )

    /**
     * Atomically promotes a [:MEMBER] relation to [:ADMIN], preserving all properties
     * (notably `starred`) from the old relation onto the new one.
     *
     * Returns the number of [:MEMBER] relations deleted (0 = user had no MEMBER edge;
     * 1 = promotion succeeded). The [:ADMIN] edge is always created-or-kept regardless.
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[old:MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        MERGE (u)-[newRel:ADMIN]->(e)
        SET newRel += properties(old)
        DELETE old
        RETURN count(old)
    """,
    )
    fun promoteMemberToAdmin(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Long

    /**
     * Atomically demotes a [:ADMIN] relation to [:MEMBER], preserving all properties
     * (notably `starred`) from the old relation onto the new one.
     *
     * Returns the number of [:ADMIN] relations deleted (0 = user had no ADMIN edge;
     * 1 = demotion succeeded). The [:MEMBER] edge is always created-or-kept regardless.
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[old:ADMIN]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        MERGE (u)-[newRel:MEMBER]->(e)
        SET newRel += properties(old)
        DELETE old
        RETURN count(old)
    """,
    )
    fun demoteAdminToMember(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Long

    // Star / favorite — a per-user boolean property on the user↔entity relation.

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        SET r.starred = $starred
        RETURN count(r)
    """,
    )
    fun setStarred(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("starred") starred: Boolean,
    ): Long

    /**
     * The caller's direct relation and starred flag for every entity of the given label
     * they have a direct ADMIN/MEMBER edge on, encoded as `"id|relation|starred"` per row.
     *
     * Encoded into a single string column on purpose: Spring Data Neo4j cannot map a
     * multi-value record (`RETURN a, b, c`) without a custom mapper. The caller decodes
     * each row and collapses duplicate ids (a user may hold both edges on one entity).
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id + '|' + type(r) + '|' + toString(coalesce(r.starred, false)) AS row
    """,
    )
    fun findDirectRelations(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    // User listing queries

    @Query(
        $$"""
        MATCH (u:User)-[r:ADMIN|MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN u.id
    """,
    )
    fun findUsersWithAnyPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    @Query(
        $$"""
        MATCH (u:User)-[r:ADMIN]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN u.id
    """,
    )
    fun findUsersWithAdminPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    @Query(
        $$"""
        MATCH (u:User)-[r:MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        RETURN u.id
    """,
    )
    fun findUsersWithMemberPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    // Entity listing queries

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN]->(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id
    """,
    )
    fun findEntitiesWhereUserIsAdmin(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[r:ADMIN|MEMBER]->(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id
    """,
    )
    fun findEntitiesWhereUserHasAccess(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[:ADMIN]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id
        UNION
        MATCH (u:User {id: $userId})-[:ADMIN]->(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id
    """,
    )
    fun findEntitiesWhereUserIsAdminTransitive(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[:ADMIN|MEMBER]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id
        UNION
        MATCH (u:User {id: $userId})-[:ADMIN|MEMBER]->(e)
        WHERE $entityLabel IN labels(e)
        RETURN e.id
    """,
    )
    fun findEntitiesWhereUserHasAccessTransitive(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    // Batch authorization queries — filter a candidate list of ids by user permission
    // in a single Cypher round-trip (1 logical query, 2 Cypher branches via UNION).
    // Same shape as findEntitiesWhereUserHasAccessTransitive / findEntitiesWhereUserIsAdminTransitive
    // but bounded by `e.id IN $ids` so the result set scales linearly with the input
    // rather than the namespace size.

    @Query(
        $$"""
        MATCH (u:User {id: $userId})
        MATCH (e)
        WHERE $entityLabel IN labels(e)
          AND e.id IN $ids
          AND (e.removed IS NULL OR e.removed = false)
          AND (
            ($checkPlatform AND e.namespaceId IS NULL AND e.userId IS NULL)
            OR EXISTS { (u)-[:ADMIN|MEMBER]->(e) }
            OR EXISTS { (e)-[:BELONGS_TO]->(n:Namespace)<-[:ADMIN|MEMBER]-(u) }
          )
        RETURN e.id
    """,
    )
    fun filterIdsWhereUserHasAccess(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("ids") ids: Collection<String>,
        @Param("checkPlatform") checkPlatform: Boolean,
    ): List<String>

    @Query(
        $$"""
        MATCH (u:User {id: $userId})
        MATCH (e)
        WHERE $entityLabel IN labels(e)
          AND e.id IN $ids
          AND (e.removed IS NULL OR e.removed = false)
          AND (
            (u.isAdmin = true AND $checkPlatform AND e.namespaceId IS NULL AND e.userId IS NULL)
            OR EXISTS { (u)-[:ADMIN]->(e) }
            OR EXISTS { (e)-[:BELONGS_TO]->(n:Namespace)<-[:ADMIN]-(u) }
          )
        RETURN e.id
    """,
    )
    fun filterIdsWhereUserIsAdmin(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("ids") ids: Collection<String>,
        @Param("checkPlatform") checkPlatform: Boolean,
    ): List<String>

    /**
     * Batch-apply ADMIN role to users on an entity.
     *
     * For each userId in [userIds]:
     * - Deletes any existing [:MEMBER] relation and creates/keeps [:ADMIN].
     * - Creates [:ADMIN] directly when no relation exists.
     * - No-op when [:ADMIN] already exists and no [:MEMBER] needs deleting.
     * - Silently skips non-existent User nodes (the `MATCH (u:User {id: uid})` filters them).
     *
     * Returns the userIds that were successfully processed.
     */
    // TODO: once [:STARRED] is a dedicated relationship (see linked issue), the
    //  existing promoteMemberToAdmin / demoteAdminToMember queries can be simplified
    //  the same way — plain delete + merge without property copying.
    @Query("UNWIND \$userIds AS uid MATCH (u:User {id: uid}) MATCH (e {id: \$entityId}) WHERE \$entityLabel IN labels(e) OPTIONAL MATCH (u)-[oldMember:MEMBER]->(e) DELETE oldMember MERGE (u)-[:ADMIN]->(e) RETURN uid")
    fun batchGrantAdmin(
        @Param("userIds") userIds: List<String>,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    /**
     * Batch-apply MEMBER role to users on an entity.
     *
     * For each userId in [userIds]:
     * - Deletes any existing [:ADMIN] relation and creates/keeps [:MEMBER].
     * - Creates [:MEMBER] directly when no relation exists.
     * - No-op when [:MEMBER] already exists and no [:ADMIN] needs deleting.
     * - Silently skips non-existent User nodes.
     *
     * Returns the userIds that were successfully processed.
     */
    @Query("UNWIND \$userIds AS uid MATCH (u:User {id: uid}) MATCH (e {id: \$entityId}) WHERE \$entityLabel IN labels(e) OPTIONAL MATCH (u)-[oldAdmin:ADMIN]->(e) DELETE oldAdmin MERGE (u)-[:MEMBER]->(e) RETURN uid")
    fun batchGrantMember(
        @Param("userIds") userIds: List<String>,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    /**
     * Batch-revoke all relations ([:ADMIN] and [:MEMBER]) from users on an entity.
     *
     * Silently skips non-existent User nodes and users without any relation.
     * Returns the userIds for which at least one relation was removed.
     */
    @Query(
        $$"""
        UNWIND $userIds AS uid
        MATCH (u:User {id: uid})-[r:ADMIN|MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        DELETE r
        RETURN DISTINCT uid
        """,
    )
    fun batchRevoke(
        @Param("userIds") userIds: List<String>,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    /**
     * Returns true when an entity with [entityId] and label [entityLabel] is
     * genuinely platform-scoped: both [namespaceId] and [userId] scalar properties
     * are NULL on the node.
     *
     * Guards against user-global overlays (namespaceId IS NULL, userId IS NOT NULL)
     * being mistakenly treated as platform-readable by any authenticated user.
     */
    @Query(
        $$"""MATCH (e {id: $entityId})
        WHERE $entityLabel IN labels(e)
          AND e.namespaceId IS NULL
          AND e.userId IS NULL
        RETURN COUNT(e) > 0""",
    )
    fun isPlatformScoped(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Boolean
}
