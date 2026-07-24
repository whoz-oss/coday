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

    /**
     * Grants [:ADMIN], replacing any existing [:MEMBER] edge — a user holds a single
     * permission relation per entity (see [PermissionRelation]).
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})
        MATCH (e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        OPTIONAL MATCH (u)-[oldMember:MEMBER]->(e)
        DELETE oldMember
        MERGE (u)-[r:ADMIN]->(e)
        RETURN r
    """,
    )
    fun createAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    )

    /**
     * Grants [:MEMBER], replacing any existing [:ADMIN] edge — a user holds a single
     * permission relation per entity (see [PermissionRelation]).
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})
        MATCH (e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        OPTIONAL MATCH (u)-[oldAdmin:ADMIN]->(e)
        DELETE oldAdmin
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
     * Atomically promotes a [:MEMBER] relation to [:ADMIN].
     *
     * The [:STARRED] edge (if any) is untouched — it is a separate relationship and
     * survives the permission-type change without any property-copying logic.
     *
     * Returns the number of [:MEMBER] relations deleted (0 = user had no MEMBER edge —
     * the query is then a no-op and creates nothing; 1 = promotion succeeded).
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[old:MEMBER]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        MERGE (u)-[:ADMIN]->(e)
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
     * Atomically demotes a [:ADMIN] relation to [:MEMBER].
     *
     * The [:STARRED] edge (if any) is untouched — it is a separate relationship and
     * survives the permission-type change without any property-copying logic.
     *
     * Returns the number of [:ADMIN] relations deleted (0 = user had no ADMIN edge —
     * the query is then a no-op and creates nothing; 1 = demotion succeeded).
     */
    @Query(
        $$"""
        MATCH (u:User {id: $userId})-[old:ADMIN]->(e {id: $entityId})
        WHERE $entityLabel IN labels(e)
        MERGE (u)-[:MEMBER]->(e)
        DELETE old
        RETURN count(old)
    """,
    )
    fun demoteAdminToMember(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): Long

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
     * Batch-sets the ADMIN role on users of an entity. The [:ADMIN] edge replaces any
     * [:MEMBER] edge — a user holds a single permission relation per entity
     * (see [PermissionRelation]).
     *
     * For each userId in [userIds]:
     * - Deletes any existing [:MEMBER] relation and creates/keeps [:ADMIN].
     * - Creates [:ADMIN] directly when no relation exists.
     * - No-op when [:ADMIN] already exists and no [:MEMBER] needs deleting.
     * - Silently skips non-existent User nodes (the `MATCH (u:User {id: uid})` filters them).
     *
     * The [:STARRED] edge (if any) is untouched — it is a separate relationship and
     * survives the permission-type change without any property-copying logic.
     *
     * Returns the userIds that were successfully processed.
     */
    @Query(
        $$"""
            UNWIND $userIds AS uid
            MATCH (u:User {id: uid})
            MATCH (e {id: $entityId})
            WHERE $entityLabel IN labels(e)
            OPTIONAL MATCH (u)-[oldMember:MEMBER]->(e)
            DELETE oldMember
            MERGE (u)-[:ADMIN]->(e)
            RETURN uid
            """,
    )
    fun batchSetAdminRole(
        @Param("userIds") userIds: List<String>,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
    ): List<String>

    /**
     * Batch-sets the MEMBER role on users of an entity. The [:MEMBER] edge replaces any
     * [:ADMIN] edge — a user holds a single permission relation per entity
     * (see [PermissionRelation]).
     *
     * For each userId in [userIds]:
     * - Deletes any existing [:ADMIN] relation and creates/keeps [:MEMBER].
     * - Creates [:MEMBER] directly when no relation exists.
     * - No-op when [:MEMBER] already exists and no [:ADMIN] needs deleting.
     * - Silently skips non-existent User nodes.
     *
     * The [:STARRED] edge (if any) is untouched — it is a separate relationship and
     * survives the permission-type change without any property-copying logic.
     *
     * Returns the userIds that were successfully processed.
     */
    @Query(
        $$"""
            UNWIND $userIds AS uid
            MATCH (u:User {id: uid})
            MATCH (e {id: $entityId})
            WHERE $entityLabel IN labels(e)
            OPTIONAL MATCH (u)-[oldAdmin:ADMIN]->(e)
            DELETE oldAdmin
            MERGE (u)-[:MEMBER]->(e)
            RETURN uid
            """,
    )
    fun batchSetMemberRole(
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
