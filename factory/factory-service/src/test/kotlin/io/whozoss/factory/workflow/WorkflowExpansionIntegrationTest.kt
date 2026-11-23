package io.whozoss.factory.workflow

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.agentattempt.domain.AgentStepAttemptRecord
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.AgentStepResultObservedIdentity
import io.whozoss.factory.agentattempt.domain.ResultSchemaInvalidException
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation
import io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.domain.createWorkflowInstance
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.EffectiveWorkflowDefinitionResolver
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.WorkflowExpansionService
import io.whozoss.factory.workflow.service.toPolicyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Run-time expansion of a workflow on the real Spring beans and the embedded Neo4j: a result carrying a
 * `continuation` is checked at submission, the accepted steps are added to THAT run only, and the
 * definition catalog never changes.
 */
class WorkflowExpansionIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var evidence: WorkflowEvidenceRepository
    @Autowired private lateinit var results: AgentStepResultService
    @Autowired private lateinit var attempts: AgentStepAttemptRepository
    @Autowired private lateinit var expansion: WorkflowExpansionService
    @Autowired private lateinit var catalog: SessionDefinitionCatalog
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val namespace = "expansion-ns"
    private val caseId = "case-expansion"
    private val agent = "Archay"
    private val expander = "architect-plan"

    private fun resolver() = EffectiveWorkflowDefinitionResolver(repository)

    /** Registers `architect-custom-flow@1.0.0` and creates one run of it. */
    private fun startRun(workflowId: String): WorkflowInstanceRecord {
        val record = catalog.load().single { it.workflowType == "architect-custom-flow" }
        repository.saveDefinition(scope, record)
        val input = WorkflowDefinitionInput(record.workflowType, record.version, record.definitionHash, record.toPolicyDefinition().steps)
        val created = createWorkflowInstance(
            WorkflowStartCommand(workflowId, record.workflowType, "Expansion run"),
            input,
            ControllerExecutionInput("test", "agentos", "runner"),
        )
        return repository.insertInstance(
            scope,
            WorkflowInstanceRecord(namespace, workflowId, 1, "active", created.creationCommandHash, created.instance, created.projection),
        )
    }

    private fun attemptFor(workflowId: String, attemptId: String): String {
        attempts.insert(scope, AgentStepAttemptRecord(namespace, workflowId, expander, attemptId, "agent-1", "running", 1, "{}"))
        return results.issue(
            scope,
            AgentStepResultCapabilityIdentity(attemptId, workflowId, expander, namespace, caseId, agent, "sha256:${"a".repeat(64)}"),
        ).token
    }

    private fun observed(attemptId: String) = AgentStepResultObservedIdentity(attemptId, caseId, agent, namespace)

    private fun business(continuation: String?, status: String = "PASS"): JsonNode = objectMapper.readTree(
        """{"status":"$status","summary":"plan done","claims":{"modifiedFiles":[]}""" +
            (continuation?.let { ""","continuation":$it""" } ?: "") + "}",
    )

    private fun step(id: String, dependsOn: String = "[]", agentName: String = agent) =
        """{"id":"$id","name":"Step $id","responsibility":{"kind":"agent","name":"$agentName"},"dependsOn":$dependsOn,
            "entry":["Read the plan"],"mission":"Do $id","exit":["Report"]}"""

    private val goodContinuation = """{"steps":[${step("build")},${step("review", "[\"build\"]")}]}"""

    private fun violationsOf(error: ResultSchemaInvalidException): List<*> =
        ((error.details as Map<*, *>)["violations"] as List<*>)

    @Test
    fun `a bad continuation is rejected with violations and does not use the capability up`() {
        val workflowId = "expansion-reject"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-reject")
        val bad = """{"steps":[${step("build", "[\"ghost\"]")}]}"""

        val error = org.junit.jupiter.api.Assertions.assertThrows(ResultSchemaInvalidException::class.java) {
            results.submit(scope, token, business(bad), observed("attempt-reject"), null)
        }

        assertThat(violationsOf(error)).isNotEmpty()
        assertThat(error.details.toString()).doesNotContain("ghost")
        // The capability is intact: the corrected result is accepted in the same turn.
        val accepted = results.submit(scope, token, business(goodContinuation), observed("attempt-reject"), null)
        assertThat(accepted.created).isTrue()
    }

    @Test
    fun `a PASS of the expanding step without a continuation is rejected`() {
        val workflowId = "expansion-required"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-required")

        val error = org.junit.jupiter.api.Assertions.assertThrows(ResultSchemaInvalidException::class.java) {
            results.submit(scope, token, business(null), observed("attempt-required"), null)
        }

        assertThat(violationsOf(error).toString()).contains("CONTINUATION_REQUIRED")
        // FAIL stays possible without a continuation (the step is not forced to expand on failure).
        assertThat(results.submit(scope, token, business(null, "FAIL"), observed("attempt-required"), null).created).isTrue()
    }

    @Test
    fun `an accepted continuation is added to the run only, never to the catalog, and replays add nothing`() {
        val workflowId = "expansion-graft"
        startRun(workflowId)
        val definitionsBefore = repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }
        val token = attemptFor(workflowId, "attempt-graft")
        results.submit(scope, token, business(goodContinuation), observed("attempt-graft"), null)
        val expanderStep = repository.findDefinition(scope, "architect-custom-flow", "1.0.0")!!.toPolicyDefinition().steps.single()

        assertThat(expansion.graft(scope, namespace, workflowId, expanderStep, "attempt-graft")).isTrue()

        val run = repository.findInstance(scope, namespace, workflowId)!!
        val recorded = run.instance["expansion"] as Map<*, *>
        assertThat(recorded["stepIds"]).isEqualTo(listOf("build", "review"))
        assertThat(recorded["sourceStepId"]).isEqualTo(expander)
        val projected = (run.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().associateBy { it["id"] }
        assertThat(projected.keys).containsExactly(expander, "build", "review")
        assertThat(projected.getValue("build")["dependsOn"]).isEqualTo(listOf(expander))
        assertThat(projected.getValue("build")["status"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(run.projection["expansion"]).isNotNull()
        assertThat(repository.findStepStates(scope, namespace, workflowId).map { it.stepId }).contains("build", "review")
        assertThat(evidence.list(scope, namespace, workflowId).filter { it.kind == WorkflowExpansionService.EVIDENCE_KIND }).hasSize(1)

        // The catalog is untouched; the run follows base + added steps through the single resolver.
        assertThat(repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }).isEqualTo(definitionsBefore)
        assertThat(resolver().resolve(scope, run).steps.map { it.id }).containsExactly(expander, "build", "review")

        // Idempotent: replaying the same accepted result adds nothing.
        assertThat(expansion.graft(scope, namespace, workflowId, expanderStep, "attempt-graft")).isFalse()
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.revision).isEqualTo(run.revision)
        assertThat(evidence.list(scope, namespace, workflowId).filter { it.kind == WorkflowExpansionService.EVIDENCE_KIND }).hasSize(1)
    }

    @Test
    fun `a run without an expansion keeps resolving to its base definition`() {
        val run = startRun("expansion-none")

        assertThat(resolver().resolve(scope, run).steps.map { it.id }).containsExactly(expander)
        assertThatThrownBy { expansion.expandedDefinitionDraft(scope, namespace, "expansion-none") }
            .isInstanceOf(WorkflowException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "WORKFLOW_NOT_EXPANDED")
    }

    @Test
    fun `the draft of an expanded run validates, drops the expansion declaration and registers nothing`() {
        val workflowId = "expansion-draft"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-draft")
        results.submit(scope, token, business(goodContinuation), observed("attempt-draft"), null)
        val expanderStep = repository.findDefinition(scope, "architect-custom-flow", "1.0.0")!!.toPolicyDefinition().steps.single()
        expansion.graft(scope, namespace, workflowId, expanderStep, "attempt-draft")
        val definitionsBefore = repository.listDefinitions(scope).size

        val draft = expansion.expandedDefinitionDraft(scope, namespace, workflowId)

        @Suppress("UNCHECKED_CAST")
        val definition = draft["definition"] as Map<String, Any?>
        assertThat(WorkflowDefinitionValidator.validate(definition)).isInstanceOf(WorkflowDefinitionValidation.Valid::class.java)
        val steps = (definition["steps"] as List<*>).filterIsInstance<Map<*, *>>()
        assertThat(steps.map { it["id"] }).containsExactly(expander, "build", "review")
        assertThat(steps.none { it.containsKey("expansion") }).isTrue()
        assertThat(definition["workflowType"]).isNotEqualTo("architect-custom-flow")
        assertThat((draft["source"] as Map<*, *>)["workflowType"]).isEqualTo("architect-custom-flow")
        assertThat(repository.listDefinitions(scope)).hasSize(definitionsBefore)
    }

    @Test
    fun `an altered effective definition hash is refused as a definition mismatch`() {
        val workflowId = "expansion-tamper"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-tamper")
        results.submit(scope, token, business(goodContinuation), observed("attempt-tamper"), null)
        val expanderStep = repository.findDefinition(scope, "architect-custom-flow", "1.0.0")!!.toPolicyDefinition().steps.single()
        expansion.graft(scope, namespace, workflowId, expanderStep, "attempt-tamper")
        val run = repository.findInstance(scope, namespace, workflowId)!!
        val tamperedInstance = run.instance.toMutableMap()
        tamperedInstance["expansion"] = (run.instance["expansion"] as Map<*, *>).entries
            .associate { it.key.toString() to it.value } + ("effectiveDefinitionHash" to "0".repeat(64))
        val tampered = run.copy(revision = run.revision + 1, instance = tamperedInstance)
        assertThat(repository.updateInstance(scope, namespace, workflowId, run.revision, tampered)).isTrue()

        assertThatThrownBy { resolver().resolve(scope, repository.findInstance(scope, namespace, workflowId)!!) }
            .isInstanceOf(WorkflowException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
    }

    @Test
    fun `the bundled architect custom flow loads and declares a single expanding agent step`() {
        val record = catalog.load().single { it.workflowType == "architect-custom-flow" }

        assertThat(record.version).isEqualTo("1.0.0")
        val steps = record.toPolicyDefinition().steps
        assertThat(steps.map { it.id }).containsExactly(expander)
        assertThat(steps.single().expansion).isNotNull()
        assertThat(steps.single().responsibility.name).isEqualTo(agent)
    }
}
