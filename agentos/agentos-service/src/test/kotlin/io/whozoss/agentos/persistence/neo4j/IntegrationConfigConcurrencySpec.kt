package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import io.whozoss.agentos.integrationConfig.IntegrationConfigRepository
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Concurrency contract for the `tripleKey` unique constraint introduced in story 6.2.5.
 *
 * Two threads race on the same triple. The applicative pre-check inside the service is
 * intentionally bypassed — we exercise [IntegrationConfigRepository.save] directly so the
 * DB-level constraint is the only line of defence. The expected outcome is exactly one
 * successful insert and exactly one [DataIntegrityViolationException] on the loser.
 *
 * The spec runs only against the embedded Neo4j harness: the in-memory repository has no
 * uniqueness enforcement (and is not the production target), so a race there would always
 * produce two successes and obscure the assertion.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class IntegrationConfigConcurrencySpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var repo: IntegrationConfigRepository

    @Autowired
    lateinit var namespaceRepo: NamespaceRepository

    @Autowired
    lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "concurrent inserts on same (namespace x user, name) triple — exactly one succeeds" {
            val ns = namespaceRepo.save(Namespace(metadata = EntityMetadata(), name = "test-ns"))
            val userId = UUID.randomUUID()
            assertExactlyOneRaceWinner {
                IntegrationConfig(
                    metadata = EntityMetadata(),
                    namespaceId = ns.id,
                    userId = userId,
                    name = "JIRA",
                    integrationType = "JIRA",
                )
            }
        }

        "concurrent inserts on same (namespace-only, name) triple — exactly one succeeds" {
            val ns = namespaceRepo.save(Namespace(metadata = EntityMetadata(), name = "test-ns"))
            assertExactlyOneRaceWinner {
                IntegrationConfig(
                    metadata = EntityMetadata(),
                    namespaceId = ns.id,
                    userId = null,
                    name = "JIRA",
                    integrationType = "JIRA",
                )
            }
        }

        "concurrent inserts on same (user-only, name) triple — exactly one succeeds" {
            val userId = UUID.randomUUID()
            assertExactlyOneRaceWinner {
                IntegrationConfig(
                    metadata = EntityMetadata(),
                    namespaceId = null,
                    userId = userId,
                    name = "JIRA",
                    integrationType = "JIRA",
                )
            }
        }
    }

    /**
     * Submit two concurrent saves of fresh entities (distinct ids, identical triple) and
     * assert that the unique constraint admits exactly one of them.
     *
     * [factory] is invoked once per thread so each gets its own EntityMetadata with a unique id —
     * otherwise SDN would MERGE on the shared id and skip the conflict path entirely.
     */
    private fun assertExactlyOneRaceWinner(factory: () -> IntegrationConfig) {
        val executor = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<Result<IntegrationConfig>>()

        try {
            repeat(2) {
                executor.submit {
                    latch.await()
                    results.add(runCatching { repo.save(factory()) })
                }
            }
            latch.countDown()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
        } finally {
            executor.shutdownNow()
        }

        results.count { it.isSuccess } shouldBe 1
        results.count { it.isFailure } shouldBe 1
        results
            .first { it.isFailure }
            .exceptionOrNull()
            .shouldBeInstanceOf<DataIntegrityViolationException>()
    }
}
