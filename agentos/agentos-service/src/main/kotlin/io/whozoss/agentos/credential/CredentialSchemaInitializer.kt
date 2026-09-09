package io.whozoss.agentos.credential

import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.stereotype.Component

/**
 * Idempotent Neo4j schema initialiser for [CredentialNode].
 *
 * Runs as an [ApplicationRunner] after Spring is up but before HTTP traffic is accepted.
 * The order of operations is significant:
 *
 * 1. **Pre-flight check** for duplicate `(userId, authSettingId)` pairs BEFORE invoking
 *    `CREATE CONSTRAINT`. Without this guard, pre-existing duplicates cause an opaque
 *    Neo4j error during constraint creation that gives operators no way to identify the
 *    offending rows. The query returns the first 3 offending pairs to make remediation
 *    actionable.
 * 2. **Composite unique constraint** on `(userId, authSettingId)` — the invariant that
 *    was documented in [CredentialNode] but never enforced at the storage level. A
 *    composite constraint is correct here because both fields are non-null; the Neo4j 5.x
 *    silent exemption for NULL components (documented in `IntegrationConfigSchemaInitializer`)
 *    does not apply.
 * 3. **Unique constraint on `id`** — defensive, prevents accidental id collisions.
 * 4. **Auxiliary index on `userId`** — backs [CredentialNodeNeo4jRepository.findByUserId]
 *    lookups that are not already covered by the composite constraint index.
 *
 * Active for both `neo4j` and `embedded-neo4j` persistence modes — exactly the modes
 * for which a Neo4j Driver bean is provisioned.
 */
@Component
@ConditionalOnExpression(
    "'\${agentos.persistence.mode:in-memory}' == 'neo4j' " +
        "or '\${agentos.persistence.mode:in-memory}' == 'embedded-neo4j'",
)
class CredentialSchemaInitializer(
    private val neo4jClient: Neo4jClient,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        assertNoDuplicateUserAuthSettingPairs()
        ensureUserAuthSettingUniqueConstraint()
        ensureIdUniqueConstraint()
        ensureUserIdIndex()
    }

    /**
     * Pre-flight: detect duplicate `(userId, authSettingId)` pairs BEFORE invoking
     * `CREATE CONSTRAINT`. A pre-existing duplicate would cause `CREATE CONSTRAINT` to
     * fail with an opaque Neo4j error that does not identify the offending rows.
     *
     * When duplicates are found the application refuses to start and logs an actionable
     * message: keep the most recent node (highest `modified`) and delete the others,
     * then restart.
     */
    private fun assertNoDuplicateUserAuthSettingPairs() {
        val offendingPairs =
            neo4jClient
                .query(
                    """
                    MATCH (c:Credential)
                    WITH c.userId AS userId, c.authSettingId AS authSettingId, count(*) AS dups
                    WHERE dups > 1
                    RETURN userId + '/' + authSettingId AS key ORDER BY dups DESC LIMIT 3
                    """.trimIndent(),
                )
                .fetchAs(String::class.java)
                .all()
        if (offendingPairs.isNotEmpty()) {
            error(
                "[CredentialSchema] aborting: found duplicate Credential row(s) with the same " +
                    "(userId, authSettingId) pair (${offendingPairs.size} group(s) shown, possibly more). " +
                    "For each offending pair, keep the node with the highest `modified` timestamp " +
                    "and hard-delete the others (MATCH (c:Credential) WHERE c.userId = '...' AND " +
                    "c.authSettingId = '...' WITH c ORDER BY c.modified DESC SKIP 1 DETACH DELETE c), " +
                    "then restart. Sample pairs: ${offendingPairs.joinToString(prefix = "[", postfix = "]")}.",
            )
        }
        logger.info { "[CredentialSchema] no duplicate (userId, authSettingId) pairs found" }
    }

    private fun ensureUserAuthSettingUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT credential_user_auth_setting_unique IF NOT EXISTS " +
                    "FOR (c:Credential) REQUIRE (c.userId, c.authSettingId) IS UNIQUE",
            ).run()
        logger.info { "[CredentialSchema] constraint 'credential_user_auth_setting_unique' ensured" }
    }

    private fun ensureIdUniqueConstraint() {
        neo4jClient
            .query(
                "CREATE CONSTRAINT credential_id_unique IF NOT EXISTS " +
                    "FOR (c:Credential) REQUIRE c.id IS UNIQUE",
            ).run()
        logger.info { "[CredentialSchema] constraint 'credential_id_unique' ensured" }
    }

    private fun ensureUserIdIndex() {
        neo4jClient
            .query(
                "CREATE INDEX credential_user_lookup IF NOT EXISTS FOR (c:Credential) ON (c.userId)",
            ).run()
        logger.info { "[CredentialSchema] index 'credential_user_lookup' ensured" }
    }

    companion object : KLogging()
}
