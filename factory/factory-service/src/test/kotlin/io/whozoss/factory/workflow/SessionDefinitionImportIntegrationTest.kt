package io.whozoss.factory.workflow

import io.mockk.mockk
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.AgentOsAdapterProperties
import io.whozoss.factory.adapter.agentos.AgentOsExecutionAdapter
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.capability.AgentTurnCapability
import io.whozoss.factory.capability.AgentTurnRequest
import io.whozoss.factory.capability.AgentTurnResult
import io.whozoss.factory.capability.CapabilityExecutionService
import io.whozoss.factory.capability.CapabilityResolver
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.hashWorkflowDefinition
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.SessionRunService
import io.whozoss.factory.workflow.service.WorkflowDefinitionSeeder
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowProjectionEvents
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Integration tests of the W8.4 declarative session import + multi-lane
 * projection.
 *
 * Extends the shared [Neo4jDomainIntegrationTest] fixture (one Spring context, one
 * in-process embedded Neo4j) — no new `@SpringBootTest` variant. Covers:
 *  - the bundled `forge-controller-v1-searcher` definition loads, validates and
 *    registers through the definition service (import/upsert surface), carrying
 *    its `execution` plugin selection;
 *  - the seeder imports the bundled catalogue idempotently;
 *  - a session run persists a projection whose steps carry the lane
 *    (`agent|code|human`), the actor name, the status, the execution window and
 *    the `dependsOn` edges needed for a cockpit swimlane timeline;
 *  - the SSE stream emits a `workflow-projection-updated` event for the run.
 */
class SessionDefinitionImportIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workflowService: WorkflowService

    @Autowired
    private lateinit var sessionRunService: SessionRunService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @Autowired
    private lateinit var attemptRepository: AgentStepAttemptRepository

    @Autowired
    private lateinit var catalogue: SessionDefinitionCatalog

    @Autowired
    private lateinit var seeder: WorkflowDefinitionSeeder

    @Autowired
    private lateinit var sseHub: WorkflowSseHub

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "e5b1c1f0-6f5a-4d0e-8a3c-w84-import"

    /** Records the raw SSE frames the hub writes. */
    private class CapturingSseEmitter : SseEmitter(0L) {
        val frames = CopyOnWriteArrayList<String>()

        override fun send(builder: SseEventBuilder) {
            val data = builder.build().firstOrNull()?.data
            if (data is String) frames.add(data)
        }
    }

    // ----- fixtures ------------------------------------------------------

    private fun stepJson(id: String, kind: String, name: String, dependsOn: List<String>): Map<String, Any?> =
        linkedMapOf(
            "id" to id,
            "name" to "Step $id",
            "responsibility" to mapOf("kind" to kind, "name" to name),
            "dependsOn" to dependsOn,
        )

    private fun writeScript(name: String, body: String) {
        val dir = repoRoot.resolve("factory/verification")
        Files.createDirectories(dir)
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n", StandardCharsets.UTF_8)
        check(script.toFile().setExecutable(true)) { "could not mark $script executable" }
    }

    private fun writeManifest(vararg entries: Pair<String, String>) {
        val dir = repoRoot.resolve("factory")
        Files.createDirectories(dir)
        val verifications = entries.joinToString(",") { (name, command) -> "\"$name\": { \"command\": \"$command\" }" }
        Files.writeString(
            dir.resolve("verification.json"),
            """{ "schemaVersion": "1", "verifications": { $verifications } }""",
            StandardCharsets.UTF_8,
        )
    }

    private fun startSession(workflowType: String, workflowId: String, steps: List<Map<String, Any?>>) {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to "1.0.0",
            "title" to "Session $workflowType",
            "steps" to steps,
        )
        val valid = WorkflowDefinitionValidator.validate(raw) as WorkflowDefinitionValidation.Valid
        workflowService.registerDefinition(
            scope,
            WorkflowDefinitionRecord(
                workflowType = workflowType,
                version = "1.0.0",
                definitionHash = hashWorkflowDefinition(valid.definition),
                definition = valid.definition,
            ),
        )
        val result = workflowService.start(
            scope,
            namespace,
            WorkflowStartCommand(workflowId = workflowId, workflowType = workflowType, title = "Session $workflowType"),
            ControllerExecutionInput(runtimeId = "test-runtime", kind = "agentos", agentId = "runner", namespaceId = namespace),
        )
        assertThat(result.status).isEqualTo(201)
    }

    private fun agentSessionService(result: AgentTurnResult): SessionRunService =
        SessionRunService(
            workflowRepository,
            evidenceRepository,
            interactionRepository,
            CapabilityExecutionService(
                CapabilityResolver(
                    object : AgentTurnCapability {
                        override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult = result
                    },
                ),
                workflowRepository,
                evidenceRepository,
                interactionRepository,
                attemptRepository,
                durableAgentAttemptService = mockk<DurableAgentAttemptService>(relaxed = true),
                agentOsExecutionAdapter = mockk<AgentOsExecutionAdapter>(relaxed = true),
                agentOsAdapterProperties = AgentOsAdapterProperties(enabled = false),
            ),
            sseHub,
        )

    private fun answerHuman(workflowId: String, stepId: String, actionId: String) {
        val interaction = interactionRepository.list(scope, namespace, workflowId, openOnly = false)
            .filter { it.stepId == stepId }
            .maxByOrNull { it.revision }!!
        interactionRepository.update(
            scope,
            namespace,
            workflowId,
            interaction.interactionId,
            interaction.revision,
            interaction.copy(
                status = "closed",
                revision = interaction.revision + 1,
                payload = interaction.payload + ("response" to mapOf("actionId" to actionId)),
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun projectionSteps(workflowId: String): List<Map<String, Any?>> {
        val snapshot = workflowService.getProjection(scope, namespace, workflowId)
        val projection = snapshot["projection"] as? Map<String, Any?> ?: error("no projection for $workflowId")
        return projection["steps"] as? List<Map<String, Any?>> ?: error("no steps for $workflowId")
    }

    private fun step(workflowId: String, stepId: String): Map<String, Any?> =
        projectionSteps(workflowId).first { it["id"] == stepId }

    // ----- import & seed -------------------------------------------------

    @Test
    fun `bundled forge controller session definition loads, validates and registers`() {
        val records = catalogue.load()
        val record = records.first { it.workflowType == FORGE_CONTROLLER_TYPE }

        assertThat(record.version).isEqualTo("1.0.0")
        @Suppress("UNCHECKED_CAST")
        val steps = record.definition["steps"] as List<Map<String, Any?>>
        assertThat(steps.map { it["id"] }).containsExactly(
            "product-specification",
            "product-approval",
            "ux-assessment",
            "technical-assessment",
            "ux-design",
            "codebase-research",
            "technical-design",
            "specification-approval",
            "frontend-implementation",
            "frontend-verification",
            "backend-implementation",
            "backend-verification",
            "code-review",
            "functional-approval",
        )
        // The controller catalogue currently declares only `agent` and `human`
        // lanes: its two verification steps are agent-driven. Running a build or
        // a test suite is a deterministic command and therefore belongs to the
        // `code` lane (an actor must not be its own oracle) — restoring that lane
        // is a pending catalogue decision, NOT a test relaxation.
        @Suppress("UNCHECKED_CAST")
        val kinds = steps.map { (it["responsibility"] as Map<String, Any?>)["kind"] }.toSet()
        assertThat(kinds).isEqualTo(setOf("agent", "human"))

        // The definition selects the Forge execution plugin; the catalogue must
        // carry that selection through to the registered record.
        assertThat(record.executionPolicy?.plugin).isEqualTo("forge")
        assertThat(record.definition["execution"]).isEqualTo(mapOf("plugin" to "forge"))

        workflowService.registerDefinition(scope, record)
        val fetched = workflowService.getDefinition(scope, FORGE_CONTROLLER_TYPE, "1.0.0")
        assertThat(fetched).isNotNull
        assertThat(fetched!!["definitionHash"]).isEqualTo(record.definitionHash)
    }

    @Test
    fun `seeder imports the bundled catalogue idempotently`() {
        // Derived from the catalogue on purpose: this test guards the seeding
        // MECHANISM (seed everything once, nothing twice), so it must stay
        // insensitive to which definitions are currently bundled.
        val bundled = catalogue.load().map { "${it.workflowType}@${it.version}" }
        assertThat(bundled).isNotEmpty

        val first = seeder.seed(scope)
        assertThat(first).containsExactlyInAnyOrderElementsOf(bundled)

        val second = seeder.seed(scope)
        assertThat(second).isEmpty()
    }

    @Test
    fun `a cyclic session definition is rejected before persistence`() {
        val raw = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "cyclic-dag",
            "version" to "1.0.0",
            "title" to "Cyclic",
            "steps" to listOf(
                stepJson("a", "code", "check", listOf("b")),
                stepJson("b", "code", "check", listOf("a")),
            ),
        )
        assertThat(WorkflowDefinitionValidator.validate(raw)).isInstanceOf(WorkflowDefinitionValidation.Invalid::class.java)
        assertThat(workflowService.getDefinition(scope, "cyclic-dag", "1.0.0")).isNull()
    }

    // ----- multi-lane projection ----------------------------------------

    @Test
    fun `a session projection carries per-step lanes, statuses and execution windows`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-lanes"
        startSession(
            "lanes-dag",
            workflowId,
            listOf(
                stepJson("a", "agent", "ForgeProductWorker", emptyList()),
                stepJson("h", "human", "Product owner", listOf("a")),
                stepJson("c", "code", "check", listOf("h")),
            ),
        )
        val service = agentSessionService(AgentTurnResult.Completed("PASS", mapOf("caseStatus" to "IDLE")))

        val first = service.runSession(scope, namespace, workflowId, repoRoot)
        assertThat(first.status).isEqualTo(WorkflowStatuses.WAITING_HUMAN)

        val agentStep = step(workflowId, "a")
        assertThat(agentStep["lane"]).isEqualTo("agent")
        assertThat(agentStep["status"]).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(agentStep["startedAt"]).isNotNull
        assertThat(agentStep["completedAt"]).isNotNull
        assertThat((agentStep["durationMs"] as Number).toLong()).isGreaterThanOrEqualTo(0L)
        @Suppress("UNCHECKED_CAST")
        val responsibility = agentStep["responsibility"] as Map<String, Any?>
        assertThat(responsibility["kind"]).isEqualTo("agent")
        assertThat(responsibility["name"]).isEqualTo("ForgeProductWorker")

        val humanStep = step(workflowId, "h")
        assertThat(humanStep["lane"]).isEqualTo("human")
        assertThat(humanStep["status"]).isEqualTo(WorkflowStatuses.WAITING_HUMAN)
        assertThat(humanStep["startedAt"]).isNotNull
        assertThat(humanStep["completedAt"]).isNull()

        val codeStep = step(workflowId, "c")
        assertThat(codeStep["lane"]).isEqualTo("code")
        assertThat(codeStep["status"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(codeStep["dependsOn"]).isEqualTo(listOf("h"))

        answerHuman(workflowId, "h", "approve")
        val second = service.runSession(scope, namespace, workflowId, repoRoot)
        assertThat(second.status).isEqualTo(WorkflowStatuses.COMPLETED)

        val resumedHuman = step(workflowId, "h")
        assertThat(resumedHuman["status"]).isEqualTo(WorkflowStatuses.COMPLETED)
        assertThat(resumedHuman["completedAt"]).isNotNull
        assertThat(step(workflowId, "c")["status"]).isEqualTo(WorkflowStatuses.COMPLETED)
    }

    @Test
    fun `a session run emits a workflow-projection-updated SSE frame`() {
        writeScript("pass", "exit 0")
        writeManifest("check" to "./factory/verification/pass")
        val workflowId = "wf-lanes-sse"
        startSession(
            "lanes-sse-dag",
            workflowId,
            listOf(stepJson("c", "code", "check", emptyList())),
        )

        val emitter = CapturingSseEmitter()
        sseHub.register(scope, namespace, emitter)

        sessionRunService.runSession(scope, namespace, workflowId, repoRoot)

        assertThat(emitter.frames).anyMatch {
            it.startsWith("event: ${WorkflowProjectionEvents.UPDATED}\ndata: ") && it.contains(workflowId)
        }
    }

    private companion object {
        const val FORGE_CONTROLLER_TYPE = "forge-controller-v1-searcher"
    }
}
