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
 */
interface PermissionNodeNeo4jRepository : Neo4jRepository<UserNode, String> {

    // Direct permission queries

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN|MEMBER]->(e {id: ${'$'}entityId})
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN type(r) AS relation
    """)
    fun findDirectPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): String?

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN]->(e {id: ${'$'}entityId})
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN COUNT(r) > 0
    """)
    fun hasAdminPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): Boolean

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN|MEMBER]->(e {id: ${'$'}entityId})
        WHERE labels(e) = [${'$'}entityLabel]
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
        WHERE labels(e) = [${'$'}entityLabel]
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
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN COUNT(e) > 0
    """)
    fun hasReadAccessViaNamespace(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String
    ): Boolean

    // Permission management queries

    @Query("""
        MATCH (u:User {id: ${'$'}userId})
        MATCH (e {id: ${'$'}entityId})
        WHERE labels(e) = [${'$'}entityLabel]
        MERGE (u)-[r:${'$'}relation]->(e)
        RETURN r
    """)
    fun createPermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("relation") relation: String
    )

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:${'$'}relation]->(e {id: ${'$'}entityId})
        WHERE labels(e) = [${'$'}entityLabel]
        DELETE r
    """)
    fun deletePermission(
        @Param("userId") userId: String,
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("relation") relation: String
    )

    // User listing queries

    @Query("""
        MATCH (u:User)-[r]->(e {id: ${'$'}entityId})
        WHERE labels(e) = [${'$'}entityLabel]
        AND (${'$'}relation IS NULL OR type(r) = ${'$'}relation)
        RETURN u.id
    """)
    fun findUsersWithPermission(
        @Param("entityId") entityId: String,
        @Param("entityLabel") entityLabel: String,
        @Param("relation") relation: String?
    ): List<String>

    // Entity listing queries

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN]->(e)
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN e.id
    """)
    fun findEntitiesWhereUserIsAdmin(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[r:ADMIN|MEMBER]->(e)
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN e.id
    """)
    fun findEntitiesWhereUserHasAccess(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN e.id
        UNION
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN]->(e)
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN e.id
    """)
    fun findEntitiesWhereUserIsAdminTransitive(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>

    @Query("""
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(n:Namespace)<-[:BELONGS_TO]-(e)
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN e.id
        UNION
        MATCH (u:User {id: ${'$'}userId})-[:ADMIN|MEMBER]->(e)
        WHERE labels(e) = [${'$'}entityLabel]
        RETURN e.id
    """)
    fun findEntitiesWhereUserHasAccessTransitive(
        @Param("userId") userId: String,
        @Param("entityLabel") entityLabel: String
    ): List<String>
}