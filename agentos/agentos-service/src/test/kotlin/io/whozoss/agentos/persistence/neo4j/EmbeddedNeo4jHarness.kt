package io.whozoss.agentos.persistence.neo4j

import mu.KLogging
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

/**
 * The single Neo4j test harness of a test run.
 *
 * Mirrors [Neo4jContainerSpec]: one database started on first use and shared by every Spring test
 * context of the `embedded-neo4j` profile. One harness per context cost about 5 s to start and about
 * 12 s to stop — the stop being a full store checkpoint, one `fsync` per store file — so the 14
 * contexts of `agentos-service:test` spent minutes starting and stopping databases rather than
 * running tests.
 *
 * A shared database means a spec no longer gets a clean one for free, and the order specs run in
 * becomes visible. A spec that needs a particular state names its own fixtures, with ids it generates
 * itself, so that what another spec left behind cannot match them. A spec that needs the local
 * identity to be a super-admin extends [io.whozoss.agentos.security.SuperAdminCallerSpec], which
 * states that requirement rather than inheriting it from an empty database.
 *
 * [Neo4jContainerSupport.clearDatabase] is not an isolation primitive here: it deletes every node of
 * the run-wide database, including other specs' super-admin identity and the migration markers written
 * by `Neo4jPersistenceConfiguration`. Many specs of this profile call it all the same, and that holds
 * only because `ProjectConfig` sets `parallelism = 1` — specs run one at a time, so a wipe never lands
 * inside another spec, and each spec sets up what it needs on its way in. Two things would break it:
 * raising the parallelism, and writing a spec that expects data another spec created.
 *
 * Deliberately never closed, like the container of [Neo4jContainerSpec]: the database has to outlive
 * the Spring test contexts, which close on the JVM shutdown hook, after Kotest's last hook has run.
 * Closing it earlier pulls the database out from under their shutdown, and closing it at all pays the
 * checkpoint this class exists to avoid. Its store sits in a directory of its own under `build/`,
 * never a path another JVM could be using, and a shutdown hook unlinks it; on POSIX that is safe while
 * the database still holds the files open. That hook runs concurrently with Spring's own and its
 * failure is only logged, so `gradle clean` on the parent directory stays the guarantee.
 */
object EmbeddedNeo4jHarness : KLogging() {
    /** Parent of the per-JVM store directories, under `build/` so `gradle clean` reclaims it. */
    private val STORE_PARENT: Path = Path.of("build", "test-neo4j-harness")

    private val harness =
        lazy {
            val storeDir = Files.createTempDirectory(STORE_PARENT.createDirectories(), "jvm-")
            logger.info { "[EmbeddedNeo4j-Test] Starting the shared Neo4j harness in $storeDir" }
            deleteOnExit(storeDir)
            Neo4jBuilders
                .newInProcessBuilder(storeDir)
                // Bolt only: the HTTP server cannot start on this classpath (javax Jersey 2 vs Boot's Jersey 3).
                // To look at the data, stop on a breakpoint that suspends only its thread and connect cypher-shell,
                // or a Neo4j Browser served over http, to EmbeddedNeo4jHarness.instance.boltURI(), no auth.
                .withDisabledServer()
                .build()
                .also { logger.info { "[EmbeddedNeo4j-Test] Harness Bolt URI: ${it.boltURI()}" } }
        }

    val instance: Neo4j
        get() = harness.value

    @OptIn(ExperimentalPathApi::class)
    private fun deleteOnExit(storeDir: Path) {
        Runtime.getRuntime().addShutdownHook(
            Thread {
                runCatching { storeDir.deleteRecursively() }
                    .onFailure { logger.warn(it) { "[EmbeddedNeo4j-Test] Could not delete $storeDir" } }
            },
        )
    }
}
