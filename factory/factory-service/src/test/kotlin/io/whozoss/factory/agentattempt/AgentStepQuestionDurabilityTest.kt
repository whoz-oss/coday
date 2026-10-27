package io.whozoss.factory.agentattempt

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.QuestionAlreadyAnsweredException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.DurableAgentAttemptRepository
import io.whozoss.factory.agentattempt.persistence.Neo4jAgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.Neo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.Neo4jDurableAgentAttemptRepository
import io.whozoss.factory.agentattempt.persistence.Neo4jIdempotencyRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jAgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jDurableAgentAttemptJournalRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jDurableAgentAttemptRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jIdempotencyRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
import io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jResultCapabilityRepository
import io.whozoss.factory.agentattempt.service.AgentStepQuestionService
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.persistence.HumanInteractionLookupRepository
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.Neo4jHumanInteractionRepository
import io.whozoss.factory.workflow.persistence.SpringDataNeo4jHumanInteractionEventRepository
import io.whozoss.factory.workflow.persistence.SpringDataNeo4jHumanInteractionRepository
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
 * Restart-durability proof of the Phase 4 ask-step-question channel across a
 * **real restart** of the Neo4j engine (same harness technique as
 * [AgentStepResultDurabilityTest]).
 *
 * Phase 1 boots the stack, drives a durable attempt to `running`, asks a step
 * question (attempt `waiting_human` + durable `agent_question` interaction)
 * and stops the DBMS. Phase 2 boots a BRAND-NEW DBMS over the first
 * generation's on-disk bytes with new repository/service instances and asserts
 * the parked attempt and the waiting interaction survived; it then answers the
 * question and asserts attempt N is superseded and attempt N+1 persisted with
 * its resumption context — everything reloaded from disk.
 */
class AgentStepQuestionDurabilityTest {

    @TempDir
    lateinit var neo4jDir: Path

    private val scope = TenantScope("org-q-restart", "ws-q-restart")
    private val namespace = "ns-q-restart"
    private val workflow = "wf-q-restart"
    private val step = "step-q-restart"
    private val caseId = "case-q-restart"
    private val agentName = "Worker"
    private val attemptId = "attempt-q-restart"
    private val base = Instant.parse("2026-06-01T00:00:00Z")

    @Test
    fun `a waiting step question survives a restart and its answer supersedes N and persists N+1`() {
        var interactionId: String

        // Phase 1 — first boot: run the attempt, ask the question, snapshot.
        val firstBoot = startStack()
        try {
            seedRunningAttemptWithCapability(firstBoot).let { token ->
                val asked = firstBoot.questions.ask(
                    scope, token, attemptId,
                    firstBoot.objectMapper.readTree(
                        """{"prompt":"Proceed after restart?","type":"FREE_TEXT","contextHash":"sha256:restart-q"}""",
                    ),
                    caseId, agentName, null, base.plusSeconds(5),
                )
                interactionId = asked.interactionId
                assertThat(asked.status).isEqualTo("waiting_human")
            }
        } finally {
            firstBoot.close()
        }
        val snapshot = stopAndSnapshot(firstBoot)

        // Phase 2 — a brand-new DBMS over the on-disk bytes of the first
        // generation: everything asserted below is reloaded from disk.
        val secondBoot = startStack(copyFrom = snapshot)
        try {
            secondBoot.let { stack ->
                // The parked attempt and the waiting interaction survived.
                val waiting = stack.attempts.find(scope, namespace, workflow, step, attemptId)!!
                assertThat(waiting.status).isEqualTo(AgentAttemptStatus.WAITING_HUMAN)
                val interaction = stack.interactions.find(scope, namespace, workflow, interactionId)!!
                assertThat(interaction.interactionType).isEqualTo("agent_question")
                assertThat(interaction.status).isEqualTo("waiting")
                assertThat(interaction.payload["attemptId"]).isEqualTo(attemptId)
                assertThat(interaction.payload["prompt"]).isEqualTo("Proceed after restart?")
                assertThat(stack.interactions.listEvents(scope, namespace, workflow).map { it.eventType })
                    .contains("agent_question_asked")
                assertThat(stack.outboxNodes.findAllByOrganization(scope.organizationId).map { it.eventType })
                    .contains("agent_question_asked")
                    .doesNotContain("agent_question_answered")

                // The answer lands after the restart: N superseded, N+1 pending.
                val answered = stack.questions.answer(
                    scope, namespace, workflow, interactionId, interaction.revision, "yes, proceed", "alice",
                    base.plusSeconds(30),
                )
                val successorId = CapabilityExecutionService.retryAttemptId(workflow, step, 2)
                assertThat(answered.supersededAttemptId).isEqualTo(attemptId)
                assertThat(answered.successorAttemptId).isEqualTo(successorId)

                assertThat(stack.attempts.find(scope, namespace, workflow, step, attemptId)!!.status)
                    .isEqualTo(AgentAttemptStatus.SUPERSEDED)
                val successor = stack.attempts.find(scope, namespace, workflow, step, successorId)!!
                assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
                assertThat(successor.attemptNumber).isEqualTo(2)
                val context = stack.objectMapper.readTree(successor.resumptionContext)
                assertThat(context.path("question").asText()).isEqualTo("Proceed after restart?")
                assertThat(context.path("answer").asText()).isEqualTo("yes, proceed")
                assertThat(context.path("actorId").asText()).isEqualTo("alice")
                assertThat(context.path("predecessorAttemptId").asText()).isEqualTo(attemptId)

                // Double-unblock protection survives the restart as well.
                assertThatThrownBy {
                    stack.questions.answer(scope, namespace, workflow, interactionId, 999, "again", "bob", base.plusSeconds(60))
                }.isInstanceOf(QuestionAlreadyAnsweredException::class.java)
                assertThat(stack.attempts.findByWorkflow(scope, namespace, workflow).map { it.attemptId })
                    .containsExactlyInAnyOrder(attemptId, successorId)
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

    private fun startStack(copyFrom: Path? = null): QuestionChannelStack {
        val builder = Neo4jBuilders
            .newInProcessBuilder(neo4jDir)
            .withDisabledServer()
        if (copyFrom != null) builder.copyFrom(copyFrom)
        val harness = builder.build()
        val context = AnnotationConfigApplicationContext()
        context.registerBean(
            Driver::class.java,
            Supplier { GraphDatabase.driver(harness.boltURI(), AuthTokens.none()) },
            BeanDefinitionCustomizer { it.destroyMethodName = "close" },
        )
        context.register(QuestionRestartPersistenceConfiguration::class.java)
        context.refresh()

        val objectMapper = jacksonObjectMapper()
        val legacyAttempts: AgentStepAttemptRepository =
            Neo4jAgentStepAttemptRepository(context.getBean(SpringDataNeo4jAgentStepAttemptRepository::class.java))
        val results = Neo4jAgentStepResultRepository(
            context.getBean(SpringDataNeo4jAgentStepResultRepository::class.java),
            context.getBean(SpringDataNeo4jResultCapabilityRepository::class.java),
            context.getBean(SpringDataNeo4jOutboxRepository::class.java),
            legacyAttempts,
            objectMapper,
        )
        val resultService = AgentStepResultService(
            results,
            Neo4jIdempotencyRepository(context.getBean(SpringDataNeo4jIdempotencyRepository::class.java)),
            legacyAttempts,
            objectMapper,
        )
        val durableAttempts: DurableAgentAttemptRepository = Neo4jDurableAgentAttemptRepository(
            context.getBean(SpringDataNeo4jDurableAgentAttemptRepository::class.java),
            context.getBean(SpringDataNeo4jDurableAgentAttemptJournalRepository::class.java),
            context.getBean(Neo4jTransactionManager::class.java),
        )
        val attemptService = DurableAgentAttemptService(durableAttempts)
        val interactions: HumanInteractionRepository = Neo4jHumanInteractionRepository(
            context.getBean(SpringDataNeo4jHumanInteractionRepository::class.java),
            HumanInteractionLookupRepository(context.getBean(Neo4jClient::class.java)),
            context.getBean(SpringDataNeo4jHumanInteractionEventRepository::class.java),
            objectMapper,
        )
        val questions = AgentStepQuestionService(
            resultService,
            attemptService,
            interactions,
            context.getBean(SpringDataNeo4jOutboxRepository::class.java),
            objectMapper,
        )
        return QuestionChannelStack(harness, context, resultService, questions, attemptService, legacyAttempts, interactions, objectMapper)
    }

    private fun stopAndSnapshot(stack: QuestionChannelStack): Path {
        val generation = java.nio.file.Files.newDirectoryStream(neo4jDir).use { stream -> stream.toList() }.single()
        stack.harness.databaseManagementService().shutdown()
        val snapshot = java.nio.file.Files.createTempDirectory("neo4j-question-restart-snapshot")
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
    @EnableNeo4jRepositories(
        basePackages = [
            "io.whozoss.factory.agentattempt.persistence",
            "io.whozoss.factory.workflow.persistence",
        ],
    )
    class QuestionRestartPersistenceConfiguration {

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

    /** One stack generation (driver + repositories + services) over a harness. */
    private class QuestionChannelStack(
        val harness: Neo4j,
        private val context: AnnotationConfigApplicationContext,
        val results: AgentStepResultService,
        val questions: AgentStepQuestionService,
        val attempts: DurableAgentAttemptService,
        val legacyAttempts: AgentStepAttemptRepository,
        val interactions: HumanInteractionRepository,
        val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    ) : AutoCloseable {
        val outboxNodes: SpringDataNeo4jOutboxRepository
            get() = context.getBean(SpringDataNeo4jOutboxRepository::class.java)

        override fun close() {
            context.close()
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedRunningAttemptWithCapability(stack: QuestionChannelStack): String {
        stack.legacyAttempts.insert(
            scope,
            AgentStepAttemptRecord(namespace, workflow, step, attemptId, "agent-1", "running", 1, "{}"),
        )
        stack.attempts.register(
            scope,
            DurableAgentAttempt(
                attemptId = attemptId,
                caseId = caseId,
                namespaceId = namespace,
                workflowId = workflow,
                stepId = step,
                attemptNumber = 1,
                agentName = agentName,
                brief = "the-turn-brief",
            ),
            base,
        )
        stack.attempts.claim(scope, namespace, workflow, step, attemptId, "owner-1", leaseTtlMs = 60_000, now = base.plusSeconds(1))
        stack.attempts.transition(scope, namespace, workflow, step, attemptId, "owner-1", AgentAttemptStatus.STARTING, now = base.plusSeconds(2))
        stack.attempts.transition(scope, namespace, workflow, step, attemptId, "owner-1", AgentAttemptStatus.RUNNING, now = base.plusSeconds(3))
        return stack.results.issue(
            scope,
            AgentStepResultCapabilityIdentity(
                attemptId = attemptId,
                workflowId = workflow,
                stepId = step,
                namespaceId = namespace,
                caseId = caseId,
                agentName = agentName,
                briefHash = "sha256:${"e".repeat(64)}",
            ),
            now = base.plusSeconds(4),
        ).token
    }
}
