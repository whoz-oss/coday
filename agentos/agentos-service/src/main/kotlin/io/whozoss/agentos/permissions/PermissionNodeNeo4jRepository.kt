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

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN|MEMBER]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN type(r) AS relation
    """)
    fun findDirectPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): String?

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN COUNT(r) > 0
    """)
    fun hasAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): Boolean

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN|MEMBER]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN COUNT(r) > 0
    """)
    fun hasMemberOrAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): Boolean

    // Transitive permission queries for namespace hierarchy

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(n:Namespace)
        MATCH (n)<-[:BELONGS_TO]-(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN COUNT(e) > 0
    """)
    fun hasAdminAccessViaNamespace(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): Boolean

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(n:Namespace)
        MATCH (n)<-[:BELONGS_TO]-(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN COUNT(e) > 0
    """)
    fun hasReadAccessViaNamespace(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): Boolean

    // Permission management queries — separate methods per relationship type
    // because Neo4j does not support parameterized relationship types.

    @Query("""
        MATCH (u:User {id: ${'$'}userId})
        MATCH (e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        MERGE (u)-[r:ADMIN]->(e)
        RETURN r
    """)
    fun createAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    )

    @Query("""
        MATCH (u:User {id: ${'$'}userId})
        MATCH (e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        MERGE (u)-[r:MEMBER]->(e)
        RETURN r
    """)
    fun createMemberPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    )

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        DELETE r
    """)
    fun deleteAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    )

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:MEMBER]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        DELETE r
    """)
    fun deleteMemberPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    )

    // User listing queries

    @Query("""
        MATCH (u:User)-[r:ADMIN|MEMBER]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN u.id
    """)
    fun findUsersWithAnyPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User)-[r:ADMIN]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN u.id
    """)
    fun findUsersWithAdminPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User)-[r:MEMBER]->(e {id: ${'$'}entityId})
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN u.id
    """)
    fun findUsersWithMemberPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    // Entity listing queries

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN]->(e)
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN e.id
    """)
    fun findEntitiesWhereUserIsAdmin(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN|MEMBER]->(e)
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN e.id
    """)
    fun findEntitiesWhereUserHasAccess(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN e.id
        UNION
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(e)
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN e.id
    """)
    fun findEntitiesWhereUserIsAdminTransitive(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN e.id
        UNION
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(e)
        WHERE ${'$'}entityLabel IN labels(e)
        RETURN e.id
    """)
    fun findEntitiesWhereUserHasAccessTransitive(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    // Batch authorization queries — filter a candidate list of ids by user permission
    // in a single Cypher round-trip (1 logical query, 2 Cypher branches via UNION).
    // Same shape as findEntitiesWhereUserHasAccessTransitive / findEntitiesWhereUserIsAdminTransitive
    // but bounded by `e.id IN $ids` so the result set scales linearly with the input
    // rather than the namespace size.

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE ${'$'}entityLabel IN labels(e) AND e.id IN ${'$'}ids
        RETURN e.id
        UNION
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(e)
        WHERE ${'$'}entityLabel IN labels(e) AND e.id IN ${'$'}ids
        RETURN e.id
    """)
    fun filterIdsWhereUserHasAccess(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("ids") ids: Collection<String>,
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE ${'$'}entityLabel IN labels(e) AND e.id IN ${'$'}ids
        RETURN e.id
        UNION
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(e)
        WHERE ${'$'}entityLabel IN labels(e) AND e.id IN ${'$'}ids
        RETURN e.id
    """)
    fun filterIdsWhereUserIsAdmin(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("ids") ids: Collection<String>,
    ): List<String>

    /**
     * Filter ids where the user has a **direct** ADMIN or MEMBER relation on the entity.
     *
     * Used for owner-private entity types (e.g. Case, WZ-32167) where namespace-level
     * ADMIN does NOT grant transitive visibility. Only a direct relation on the entity
     * node itself (placed at creation time) counts.
     */
    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(e)
        WHERE ${'$'}entityLabel IN labels(e) AND e.id IN ${'$'}ids
        RETURN e.id
    """)
    fun filterIdsWhereUserHasDirectAccess(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("ids") ids: Collection<String>,
    ): List<String>
}
