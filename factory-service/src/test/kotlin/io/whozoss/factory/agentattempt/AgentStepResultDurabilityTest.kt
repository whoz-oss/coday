package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.IdempotencyKeyCollisionException
import io.whozoss.factory.agentattempt.domain.ResultCapabilityAlreadyIssuedException
import io.whozoss.factory.agentattempt.domain.ResultSemanticCollisionException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.IdempotencyRecordNode
import io.whozoss.factory.agentattempt.persistence.Neo4jAgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.Neo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.Neo4jIdempotencyRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jIdempotencyRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jResultCapabilityRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.neo4j.core.Neo4jClient
import org.springframework.data.neo4j.core.Neo4jTemplate
import org.springframework.data.neo4j.core.mapping.Neo4jMappingContext
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories
import java.nio.file.Path
import java.time.Instant
import java.util.function.Supplier

/**
 * Durability proof of the single-use result channel across a **real restart**
 * of the Neo4j engine.
 *
 * Unlike [Neo4jIntegrationTest] (whose in-process harness lives in an ephemeral
 * directory and never shuts down mid-test), this test boots the harness on a
 * [TempDir], writes through the real repository/service stack, then **shuts
 * the DBMS down gracefully** (which checkpoints the store) and boots a
 * BRAND-NEW DBMS over the first generation's on-disk files (the harness
 * reserves a fresh random working directory per boot and deletes it on
 * `close()`, so the previous store is snapshotted and handed over
 * byte-for-byte via `copyFrom`) with a new driver, a new repository set and a
 * new service instance. Everything asserted in the second phase is therefore
 * reloaded from disk — no state can leak through a live DBMS, a driver
 * session cache or a shared in-memory object.
 *
 * The stack is assembled by hand: a minimal `@EnableNeo4jRepositories` context
 * provides the Spring Data interfaces, and the domain repositories / service
 * are constructed directly (their `@Transactional` boundaries are covered by
 * [AgentStepResultServiceIntegrationTest]; here each write commits immediately,
 * which is exactly what a restart proof needs).
 */
class AgentStepResultDurabilityTest {

    @TempDir
    lateinit var neo4jDir: Path

    private val scope = TenantScope("org-restart", "ws-restart")
    private val namespace = "ns-restart"
    private val workflow = "wf-restart"
    private val step = "step-restart"
    private val caseId = "case-restart"
    private val agentName = "Agent"
    private val briefHash = "sha256:${"c".repeat(64)}"
    private val base = Instant.parse("2026-06-01T00:00:00Z")

    @Test
    fun `a submitted result, its terminal attempt and its outbox event survive a restart`() {
        var token: String
        var resultId: String
        var resultHash: String

        // Phase 1 — first boot: issue + submit, then stop the engine and
        // snapshot its store.
        val firstBoot = startStack()
        try {
            seedAttempt(firstBoot, "attempt-restart")
            val issued = firstBoot.service.issue(scope, identity("attempt-restart"), now = base)
            val outcome = firstBoot.service.submit(
                scope,
                issued.token,
                business("PASS", "restart-proof"),
                observed("attempt-restart"),
                "restart-key",
                now = base.plusSeconds(5),
            )
            assertThat(outcome.created).isTrue()
            token = issued.token
            resultId = outcome.resultId
            resultHash = outcome.resultHash
        } finally {
            firstBoot.close()
        }
        val snapshot = stopAndSnapshot(firstBoot)

        // Phase 2 — a brand-new DBMS booted over the on-disk bytes of the first
        // generation: everything is reloaded from disk by new
        // repository/service instances.
        val secondBoot = startStack(copyFrom = snapshot)
        try {
            secondBoot.let { stack ->
            // The attempt terminalization survived.
            assertThat(stack.attempts.find(scope, namespace, workflow, step, "attempt-restart")?.status)
                .isEqualTo("completed")
            // The capability binding survived (token hash, identity, budget).
            assertThat(stack.service.resolveCapability(scope, token)?.attemptId).isEqualTo("attempt-restart")
            // The outbox event survived, still pending for the drain worker.
            val outbox = stack.outboxNodes.findAllByOrganization(scope.organizationId)
            assertThat(outbox).hasSize(1)
            assertThat(outbox.single().eventType).isEqualTo("result_submitted")
            assertThat(outbox.single().status).isEqualTo("pending")
            // The idempotency record survived.
            assertThat(
                stack.idempotencyNodes.findById(
                    IdempotencyRecordNode.compositeId(scope.organizationId, "restart-key"),
                ),
            ).isPresent

            // An identical replay is still idempotent after the restart…
            val replay = stack.service.submit(
                scope,
                token,
                business("PASS", "restart-proof"),
                observed("attempt-restart"),
                null,
                now = base.plusSeconds(10),
            )
            assertThat(replay.idempotent).isTrue()
            assertThat(replay.resultId).isEqualTo(resultId)
            assertThat(replay.resultHash).isEqualTo(resultHash)
            assertThat(countResults(stack, "attempt-restart")).isEqualTo(1)
            assertThat(stack.outboxNodes.findAllByOrganization(scope.organizationId)).hasSize(1)

            // …and through the idempotency key as well.
            val keyedReplay = stack.service.submit(
                scope,
                token,
                business("PASS", "restart-proof"),
                observed("attempt-restart"),
                "restart-key",
                now = base.plusSeconds(11),
            )
            assertThat(keyedReplay.idempotent).isTrue()
            assertThat(keyedReplay.resultId).isEqualTo(resultId)

            // A divergent replay is still a collision after the restart…
            assertThatThrownBy {
                stack.service.submit(
                    scope,
                    token,
                    business("PASS", "a-different-result"),
                    observed("attempt-restart"),
                    null,
                    now = base.plusSeconds(12),
                )
            }
                .isInstanceOf(ResultSemanticCollisionException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", "RESULT_SEMANTIC_COLLISION")

            // …including through the idempotency key layer.
            assertThatThrownBy {
                stack.service.submit(
                    scope,
                    token,
                    business("PASS", "a-different-result"),
                    observed("attempt-restart"),
                    "restart-key",
                    now = base.plusSeconds(13),
                )
            }
                .isInstanceOf(IdempotencyKeyCollisionException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", "IDEMPOTENCY_KEY_COLLISION")

            // The terminal attempt stayed immutable: still exactly one result
            // row, one outbox event and the same terminal status.
            assertThat(countResults(stack, "attempt-restart")).isEqualTo(1)
            assertThat(stack.outboxNodes.findAllByOrganization(scope.organizationId)).hasSize(1)
            assertThat(stack.attempts.find(scope, namespace, workflow, step, "attempt-restart")?.status)
                .isEqualTo("completed")
            }
        } finally {
            secondBoot.close()
            runCatching { secondBoot.harness.close() }
        }
        deleteRecursively(snapshot)
    }

    @Test
    fun `an issued capability survives a restart and stays single-use`() {
        var token: String

        // Phase 1 — issue only, then stop the engine and snapshot its store
        // (the reservation row must be durable).
        val firstBoot = startStack()
        try {
            seedAttempt(firstBoot, "attempt-issued")
            token = firstBoot.service.issue(scope, identity("attempt-issued"), now = base).token
        } finally {
            firstBoot.close()
        }
        val snapshot = stopAndSnapshot(firstBoot)

        // Phase 2 — after the restart the capability cannot be re-issued and
        // can still be redeemed exactly once.
        val secondBoot = startStack(copyFrom = snapshot)
        try {
            secondBoot.let { stack ->
            assertThatThrownBy {
                stack.service.issue(scope, identity("attempt-issued"), now = base.plusSeconds(30))
            }
                .isInstanceOf(ResultCapabilityAlreadyIssuedException::class.java)
                .hasFieldOrPropertyWithValue("errorCode", "RESULT_CAPABILITY_ALREADY_ISSUED")

            val created = stack.service.submit(
                scope,
                token,
                business("PASS", "ok"),
                observed("attempt-issued"),
                null,
                now = base.plusSeconds(40),
            )
            assertThat(created.created).isTrue()

            val replay = stack.service.submit(
                scope,
                token,
                business("PASS", "ok"),
                observed("attempt-issued"),
                null,
                now = base.plusSeconds(41),
            )
            assertThat(replay.idempotent).isTrue()
            assertThat(replay.resultId).isEqualTo(created.resultId)
            }
        } finally {
            secondBoot.close()
            runCatching { secondBoot.harness.close() }
        }
        deleteRecursively(snapshot)
    }

    // ------------------------------------------------------------------
    // Restartable stack
    // ------------------------------------------------------------------

    /**
     * Boots a fresh harness on [neo4jDir] plus a fresh repository/service stack
     * on top of it. Closing the returned stack closes the driver and shuts the
     * DBMS down gracefully — the functional equivalent of a service/container
     * restart.
     */
    private fun startStack(copyFrom: Path? = null): ResultChannelStack {
        val builder = Neo4jBuilders
            .newInProcessBuilder(neo4jDir)
            .withDisabledServer()
        // Hand the previous generation's on-disk store to the new DBMS (the
        // harness forces a fresh random working directory per boot).
        if (copyFrom != null) builder.copyFrom(copyFrom)
        val harness = builder.build()
        val context = AnnotationConfigApplicationContext()
        context.registerBean(
            Driver::class.java,
            Supplier { GraphDatabase.driver(harness.boltURI(), AuthTokens.none()) },
            BeanDefinitionCustomizer { it.destroyMethodName = "close" },
        )
        context.register(RestartPersistenceConfiguration::class.java)
        context.refresh()

        // Same feature set as the Boot-configured mapper (Kotlin data classes).
        val objectMapper = jacksonObjectMapper()
        val attempts: AgentStepAttemptRepository =
            Neo4jAgentStepAttemptRepository(context.getBean(SpringDataNeo4jAgentStepAttemptRepository::class.java))
        val results = Neo4jAgentStepResultRepository(
            context.getBean(SpringDataNeo4jAgentStepResultRepository::class.java),
            context.getBean(SpringDataNeo4jResultCapabilityRepository::class.java),
            context.getBean(SpringDataNeo4jOutboxRepository::class.java),
            attempts,
            objectMapper,
        )
        val service = AgentStepResultService(
            results,
            Neo4jIdempotencyRepository(context.getBean(SpringDataNeo4jIdempotencyRepository::class.java)),
            objectMapper,
        )
        return ResultChannelStack(harness, context, service, attempts)
    }

    /**
     * Stops the engine of [stack] gracefully (a clean shutdown checkpoints the
     * store) and returns a snapshot copy of its store files that survives the
     * harness close (which deletes its random working directory).
     */
    private fun stopAndSnapshot(stack: ResultChannelStack): Path {
        val generation = java.nio.file.Files.newDirectoryStream(neo4jDir).use { stream -> stream.toList() }.single()
        stack.harness.databaseManagementService().shutdown()
        val snapshot = java.nio.file.Files.createTempDirectory("neo4j-restart-snapshot")
        java.nio.file.Files.walk(generation).use { paths ->
            paths.forEach { source ->
                val target = snapshot.resolve(generation.relativize(source))
                if (java.nio.file.Files.isDirectory(source)) {
                    java.nio.file.Files.createDirectories(target)
                } else {
                    java.nio.file.Files.copy(source, target)
                }
            }
        }
        // Double shutdown is tolerated; close() releases the harness resources
        // and deletes its own working directory — never the snapshot.
        runCatching { stack.harness.close() }
        return snapshot
    }

    private fun deleteRecursively(dir: Path) {
        runCatching {
            java.nio.file.Files.walk(dir).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
    }

    /** Minimal Spring Data wiring, mirroring the production `Neo4jPersistenceConfiguration`. */
    @Configuration(proxyBeanMethods = false)
    @EnableNeo4jRepositories(basePackages = ["io.whozoss.factory.agentattempt.persistence"])
    class RestartPersistenceConfiguration {

        @Bean
        fun neo4jClient(driver: Driver): Neo4jClient = Neo4jClient.create(driver)

        @Bean
        fun neo4jMappingContext(): Neo4jMappingContext = Neo4jMappingContext()

        @Bean
        fun neo4jTemplate(neo4jClient: Neo4jClient, mappingContext: Neo4jMappingContext): Neo4jTemplate =
            Neo4jTemplate(neo4jClient, mappingContext)

        @Bean
        fun transactionManager(driver: Driver): Neo4jTransactionManager = Neo4jTransactionManager(driver)
    }

    /**
     * One stack generation (driver + repositories + service) over a harness.
     * Closing it closes the Spring context (and therefore the driver); the
     * engine itself is stopped by [stopAndSnapshot], which marks the restart
     * boundary.
     */
    private class ResultChannelStack(
        val harness: Neo4j,
        private val context: AnnotationConfigApplicationContext,
        val service: AgentStepResultService,
        val attempts: AgentStepAttemptRepository,
    ) : AutoCloseable {
        val resultNodes: SpringDataNeo4jAgentStepResultRepository
            get() = context.getBean(SpringDataNeo4jAgentStepResultRepository::class.java)
        val outboxNodes: SpringDataNeo4jOutboxRepository
            get() = context.getBean(SpringDataNeo4jOutboxRepository::class.java)
        val idempotencyNodes: SpringDataNeo4jIdempotencyRepository
            get() = context.getBean(SpringDataNeo4jIdempotencyRepository::class.java)

        override fun close() {
            context.close()
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedAttempt(stack: ResultChannelStack, attemptId: String) {
        stack.attempts.insert(
            scope,
            AgentStepAttemptRecord(
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptId = attemptId,
                agentId = "agent-1",
                status = "running",
                revision = 1,
                payload = "{}",
            ),
        )
    }

    private fun identity(attemptId: String): AgentStepResultCapabilityIdentity =
        AgentStepResultCapabilityIdentity(
            attemptId = attemptId,
            workflowId = workflow,
            stepId = step,
            namespaceId = namespace,
            caseId = caseId,
            agentName = agentName,
            briefHash = briefHash,
        )

    private fun observed(attemptId: String): AgentStepResultObservedIdentity =
        AgentStepResultObservedIdentity(attemptId = attemptId, caseId = caseId, agentName = agentName)

    private val fixtureMapper = jacksonObjectMapper()

    private fun business(status: String, summary: String): JsonNode =
        fixtureMapper.readTree(
            """{"status":"$status","summary":"$summary","claims":{"modifiedFiles":[]}}""",
        )

    private fun countResults(stack: ResultChannelStack, attemptId: String): Long =
        stack.resultNodes.countByAttempt(
            scope.organizationId,
            scope.workstreamId,
            namespace,
            workflow,
            step,
            attemptId,
        )
}
