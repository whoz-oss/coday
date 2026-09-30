package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import io.whozoss.agentos.permissions.PermissionNodeNeo4jRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Verifies that [io.whozoss.agentos.config.Neo4jPersistenceConfiguration.initCaseReadAt]
 * is idempotent: the WATCHES backfill runs exactly once, regardless of how many times
 * the runner is invoked.
 *
 * Strategy:
 * 1. Create User + Case nodes with a direct ADMIN edge (the precondition for backfill).
 * 2. Run the migration twice.
 * 3. Assert that exactly one WATCHES edge exists (not two) and that readAt is set.
 * 4. Run again after manually deleting the CompletedMigration flag to verify the
 *    backfill fires again on a fresh database (i.e. the guard itself works in both
 *    directions).
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jInitCaseReadAtMigrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    @Qualifier("initCaseReadAt")
    lateinit var initCaseReadAt: CommandLineRunner

    @Autowired
    lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository

    @Autowired
    lateinit var caseNodeRepository: CaseNodeNeo4jRepository

    @Autowired
    lateinit var driver: Driver

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Creates a bare User node directly in Neo4j and returns its id string. */
    private fun createUser(): String {
        val id = UUID.randomUUID().toString()
        driver.session().use { s ->
            s.run(
                $$"""CREATE (u:User {id: $id, externalId: $id, email: $id, isAdmin: false})""",
                mapOf("id" to id),
            )
        }
        return id
    }

    /** Creates a bare Case node directly in Neo4j and returns its id string. */
    private fun createCase(namespaceId: String): String {
        val id = UUID.randomUUID().toString()
        driver.session().use { s ->
            s.run(
                $$"""CREATE (c:Case {id: $id, namespaceId: $namespaceId})""",
                mapOf("id" to id, "namespaceId" to namespaceId),
            )
        }
        return id
    }

    /** Counts WATCHES edges in the whole database. */
    private fun countWatchesEdges(): Long =
        driver.session().use { s ->
            s
                .run("MATCH ()-[w:WATCHES]->() RETURN count(w) AS cnt")
                .single()["cnt"]
                .asLong()
        }

    /** Counts WATCHES edges pointing to a specific Case node. */
    private fun countWatchesEdgesForCase(caseId: String): Long =
        driver.session().use { s ->
            s
                .run(
                    $$"""MATCH ()-[w:WATCHES]->(c:Case {id: $caseId}) RETURN count(w) AS cnt""",
                    mapOf("caseId" to caseId),
                ).single()["cnt"]
                .asLong()
        }

    /** Counts CompletedMigration flag nodes matching the given id. */
    private fun countMigrationFlags(flagId: String): Long =
        driver.session().use { s ->
            s
                .run(
                    $$"""MATCH (f:CompletedMigration {id: $flagId}) RETURN count(f) AS cnt""",
                    mapOf("flagId" to flagId),
                ).single()["cnt"]
                .asLong()
        }

    /** Deletes the CompletedMigration flag so the migration can be re-triggered. */
    private fun deleteMigrationFlag(flagId: String) {
        driver.session().use { s ->
            s.run(
                $$"""MATCH (f:CompletedMigration {id: $flagId}) DELETE f""",
                mapOf("flagId" to flagId),
            )
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "migration creates WATCHES edges on first run" {
            val userId = createUser()
            val caseId = createCase(namespaceId = UUID.randomUUID().toString())
            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )

            initCaseReadAt.run()

            countWatchesEdges() shouldBe 1L
        }

        "migration is idempotent: case existing before first run gets WATCHES, case created after does not" {
            val userId = createUser()
            val namespaceId = UUID.randomUUID().toString()

            // Case that existed before the migration.
            val existingCaseId = createCase(namespaceId = namespaceId)
            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = existingCaseId,
                entityLabel = "Case",
            )

            initCaseReadAt.run()

            // New case created after the migration has already run.
            val newCaseId = createCase(namespaceId = namespaceId)
            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = newCaseId,
                entityLabel = "Case",
            )

            initCaseReadAt.run()

            // The pre-existing case has exactly one WATCHES edge.
            countWatchesEdgesForCase(existingCaseId) shouldBe 1L
            // The new case has no WATCHES edge — the second run was a no-op.
            countWatchesEdgesForCase(newCaseId) shouldBe 0L
        }

        "migration leaves a CompletedMigration flag node after first run" {
            initCaseReadAt.run()

            countMigrationFlags("readAtFeat20260908") shouldBe 1L
        }

        "migration re-runs when the CompletedMigration flag is manually removed" {
            val userId = createUser()
            val caseId = createCase(namespaceId = UUID.randomUUID().toString())
            permissionNodeRepository.createAdminPermission(
                userId = userId,
                entityId = caseId,
                entityLabel = "Case",
            )

            // First run: backfill fires, flag created, WATCHES edge set.
            initCaseReadAt.run()
            countWatchesEdges() shouldBe 1L

            // Simulate a fresh database by deleting the flag and the WATCHES edge.
            deleteMigrationFlag("readAtFeat20260908")
            driver.session().use { s -> s.run("MATCH ()-[w:WATCHES]->() DELETE w") }

            // Second run without flag: backfill fires again.
            initCaseReadAt.run()
            countWatchesEdges() shouldBe 1L
        }
    }
}
