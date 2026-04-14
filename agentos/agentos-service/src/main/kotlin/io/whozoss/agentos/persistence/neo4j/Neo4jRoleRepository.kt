package io.whozoss.agentos.persistence.neo4j

import io.whozoss.agentos.auth.MembershipInfo
import io.whozoss.agentos.auth.RoleRepository
import io.whozoss.agentos.sdk.auth.CaseRole
import io.whozoss.agentos.sdk.auth.NamespaceRole
import mu.KLogging
import org.neo4j.driver.Driver
import java.time.Instant

/**
 * Neo4j-backed implementation of [RoleRepository].
 *
 * Uses the Neo4j Java [Driver] directly (not Spring Data Neo4j repositories)
 * because MEMBER_OF and PARTICIPANT_IN are **relationships with properties**,
 * which SDN's `@Relationship` annotation handles poorly.
 *
 * All Cypher queries:
 * - Filter soft-deleted nodes: `WHERE u.removed IS NULL OR u.removed = false`
 * - Stay under 10 lines each
 * - Use `MERGE` for upsert semantics on assign operations
 *
 * IDs are [String] to match the `@Id` convention across all Neo4j nodes.
 */
open class Neo4jRoleRepository(
    private val driver: Driver,
) : RoleRepository {

    // -------------------------------------------------------------------------
    // Root status
    // -------------------------------------------------------------------------

    override fun isRoot(userId: String): Boolean =
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId})
                WHERE (u.removed IS NULL OR u.removed = false) AND u.isRoot = true
                RETURN count(u) > 0 AS isRoot
                """.trimIndent(),
                mapOf("userId" to userId),
            ).single()["isRoot"].asBoolean()
        }

    override fun setRoot(userId: String, isRoot: Boolean) {
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId})
                SET u.isRoot = ${'$'}isRoot
                """.trimIndent(),
                mapOf("userId" to userId, "isRoot" to isRoot),
            )
        }
    }

    // -------------------------------------------------------------------------
    // Namespace roles (MEMBER_OF)
    // -------------------------------------------------------------------------

    override fun findNamespaceRole(userId: String, namespaceId: String): NamespaceRole? =
        driver.session().use { session ->
            val result = session.run(
                """
                MATCH (u:User {id: ${'$'}userId})-[m:MEMBER_OF]->(n:Namespace {id: ${'$'}namespaceId})
                WHERE (u.removed IS NULL OR u.removed = false)
                  AND (n.removed IS NULL OR n.removed = false)
                RETURN m.role AS role
                """.trimIndent(),
                mapOf("userId" to userId, "namespaceId" to namespaceId),
            )
            when {
                result.hasNext() -> NamespaceRole.valueOf(result.single()["role"].asString())
                else -> null
            }
        }

    override fun assignNamespaceRole(userId: String, namespaceId: String, role: NamespaceRole, grantedBy: String) {
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId}), (n:Namespace {id: ${'$'}namespaceId})
                MERGE (u)-[m:MEMBER_OF]->(n)
                SET m.role = ${'$'}role, m.grantedAt = ${'$'}grantedAt, m.grantedBy = ${'$'}grantedBy
                """.trimIndent(),
                mapOf(
                    "userId" to userId,
                    "namespaceId" to namespaceId,
                    "role" to role.name,
                    "grantedAt" to Instant.now().toString(),
                    "grantedBy" to grantedBy,
                ),
            )
        }
    }

    override fun removeNamespaceRole(userId: String, namespaceId: String) {
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId})-[m:MEMBER_OF]->(n:Namespace {id: ${'$'}namespaceId})
                DELETE m
                """.trimIndent(),
                mapOf("userId" to userId, "namespaceId" to namespaceId),
            )
        }
    }

    override fun findMembersOfNamespace(namespaceId: String): List<MembershipInfo> =
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User)-[m:MEMBER_OF]->(n:Namespace {id: ${'$'}namespaceId})
                WHERE (u.removed IS NULL OR u.removed = false)
                  AND (n.removed IS NULL OR n.removed = false)
                RETURN u.id AS userId, m.role AS role, m.grantedAt AS grantedAt, m.grantedBy AS grantedBy
                """.trimIndent(),
                mapOf("namespaceId" to namespaceId),
            ).list { record ->
                MembershipInfo(
                    userId = record["userId"].asString(),
                    role = NamespaceRole.valueOf(record["role"].asString()),
                    grantedAt = Instant.parse(record["grantedAt"].asString()),
                    grantedBy = record["grantedBy"].asString(),
                )
            }
        }

    override fun countOwnersInNamespace(namespaceId: String): Int =
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User)-[m:MEMBER_OF {role: 'OWNER'}]->(n:Namespace {id: ${'$'}namespaceId})
                WHERE (u.removed IS NULL OR u.removed = false)
                  AND (n.removed IS NULL OR n.removed = false)
                RETURN count(u) AS ownerCount
                """.trimIndent(),
                mapOf("namespaceId" to namespaceId),
            ).single()["ownerCount"].asInt()
        }

    override fun findNamespaceIdsForUser(userId: String): Set<String> =
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId})-[:MEMBER_OF]->(n:Namespace)
                WHERE (u.removed IS NULL OR u.removed = false)
                  AND (n.removed IS NULL OR n.removed = false)
                RETURN n.id AS namespaceId
                """.trimIndent(),
                mapOf("userId" to userId),
            ).list { it["namespaceId"].asString() }.toSet()
        }

    // -------------------------------------------------------------------------
    // Case roles (PARTICIPANT_IN)
    // -------------------------------------------------------------------------

    override fun findCaseRole(userId: String, caseId: String): CaseRole? =
        driver.session().use { session ->
            val result = session.run(
                """
                MATCH (u:User {id: ${'$'}userId})-[p:PARTICIPANT_IN]->(c:Case {id: ${'$'}caseId})
                WHERE (u.removed IS NULL OR u.removed = false)
                  AND (c.removed IS NULL OR c.removed = false)
                RETURN p.role AS role
                """.trimIndent(),
                mapOf("userId" to userId, "caseId" to caseId),
            )
            when {
                result.hasNext() -> CaseRole.valueOf(result.single()["role"].asString())
                else -> null
            }
        }

    override fun assignCaseRole(userId: String, caseId: String, role: CaseRole, grantedBy: String) {
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId}), (c:Case {id: ${'$'}caseId})
                MERGE (u)-[p:PARTICIPANT_IN]->(c)
                SET p.role = ${'$'}role, p.grantedAt = ${'$'}grantedAt, p.grantedBy = ${'$'}grantedBy
                """.trimIndent(),
                mapOf(
                    "userId" to userId,
                    "caseId" to caseId,
                    "role" to role.name,
                    "grantedAt" to Instant.now().toString(),
                    "grantedBy" to grantedBy,
                ),
            )
        }
    }

    override fun removeCaseRole(userId: String, caseId: String) {
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId})-[p:PARTICIPANT_IN]->(c:Case {id: ${'$'}caseId})
                DELETE p
                """.trimIndent(),
                mapOf("userId" to userId, "caseId" to caseId),
            )
        }
    }

    override fun findAccessibleCaseIdsForUser(userId: String, namespaceId: String): Set<String> =
        driver.session().use { session ->
            session.run(
                """
                MATCH (u:User {id: ${'$'}userId})-[:MEMBER_OF]->(n:Namespace {id: ${'$'}namespaceId})
                MATCH (c:Case)-[:BELONGS_TO]->(n)
                WHERE (u.removed IS NULL OR u.removed = false)
                  AND (n.removed IS NULL OR n.removed = false)
                  AND (c.removed IS NULL OR c.removed = false)
                RETURN c.id AS caseId
                """.trimIndent(),
                mapOf("userId" to userId, "namespaceId" to namespaceId),
            ).list { it["caseId"].asString() }.toSet()
        }

    companion object : KLogging()
}
