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
import io.whozoss.factory.workflow.domain.WorkflowContinuationCodes
import io.whozoss.factory.workflow.domain.WorkflowContinuationValidator
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * Run-time expansion of a workflow on the real Spring beans and the embedded Neo4j: a result carrying a
 * `continuation` is checked at submission, the accepted steps (preceded by the human `continuation-approval` step
 * Factory inserts) are added to THAT run only, and the definition catalog never changes.
 */
@Import(ExpansionPrecheckPortConfiguration::class)
class WorkflowExpansionIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired private lateinit var repository: WorkflowRepository
    @Autowired private lateinit var evidence: WorkflowEvidenceRepository
    @Autowired private lateinit var results: AgentStepResultService
    @Autowired private lateinit var attempts: AgentStepAttemptRepository
    @Autowired private lateinit var expansion: WorkflowExpansionService
    @Autowired private lateinit var resolverBean: EffectiveWorkflowDefinitionResolver
    @Autowired private lateinit var catalog: SessionDefinitionCatalog
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agents: MutableAgentList

    private val namespace = "expansion-ns"
    private val caseId = "case-expansion"
    private val agent = "Archay"
    private val expander = "architect-plan"
    private val approval = WorkflowContinuationValidator.APPROVAL_STEP_ID

    @BeforeEach
    fun resetAgents() = agents.reset()

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

    private fun step(id: String, dependsOn: String = "[]", agentName: String = agent, kind: String = "agent") =
        """{"id":"$id","name":"Step $id","responsibility":{"kind":"$kind","name":"$agentName"},"dependsOn":$dependsOn,
            "entry":["Read the plan"],"mission":"Do $id","exit":["Report"]}"""

    private val goodContinuation = """{"steps":[${step("build")},${step("review", "[\"build\"]")}]}"""

    private fun violationsOf(error: ResultSchemaInvalidException): List<*> =
        ((error.details as Map<*, *>)["violations"] as List<*>)

    private fun expanderStep() =
        repository.findDefinition(scope, "architect-custom-flow", "1.0.0")!!.toPolicyDefinition().steps.single()

    private fun evidenceCount(workflowId: String) =
        evidence.list(scope, namespace, workflowId).count { it.kind == WorkflowExpansionService.EVIDENCE_KIND }

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
    fun `a code step is refused with UNSUPPORTED_IN_CONTINUATION and the capability is not used up`() {
        val workflowId = "expansion-code-step"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-code")
        val withCode = """{"steps":[${step("verify", agentName = "check", kind = "code")}]}"""

        val error = org.junit.jupiter.api.Assertions.assertThrows(ResultSchemaInvalidException::class.java) {
            results.submit(scope, token, business(withCode), observed("attempt-code"), null)
        }

        assertThat(violationsOf(error).toString())
            .contains(WorkflowContinuationCodes.UNSUPPORTED_IN_CONTINUATION)
            .contains("code_step")
        assertThat(results.submit(scope, token, business(goodContinuation), observed("attempt-code"), null).created).isTrue()
    }

    @Test
    fun `a step that uses the id of the approval step is refused`() {
        val workflowId = "expansion-approval-id"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-approval-id")
        val clash = """{"steps":[${step(approval)}]}"""

        val error = org.junit.jupiter.api.Assertions.assertThrows(ResultSchemaInvalidException::class.java) {
            results.submit(scope, token, business(clash), observed("attempt-approval-id"), null)
        }

        assertThat(violationsOf(error).toString()).contains(WorkflowContinuationCodes.STEP_ID_COLLISION)
    }

    @Test
    fun `an unreadable agent list refuses the continuation, then the same result is accepted once the list is readable`() {
        val workflowId = "expansion-unverifiable"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-unverifiable")
        agents.agents = null

        val error = org.junit.jupiter.api.Assertions.assertThrows(ResultSchemaInvalidException::class.java) {
            results.submit(scope, token, business(goodContinuation), observed("attempt-unverifiable"), null)
        }

        assertThat(violationsOf(error).toString()).contains(WorkflowContinuationCodes.AGENTS_UNVERIFIABLE)
        // The agent can send the very same result again once the agents can be read: nothing was consumed.
        agents.reset()
        assertThat(results.submit(scope, token, business(goodContinuation), observed("attempt-unverifiable"), null).created).isTrue()
    }

    @Test
    fun `an accepted continuation is added to the run only behind the approval step, never to the catalog, and replays add nothing`() {
        val workflowId = "expansion-graft"
        startRun(workflowId)
        val definitionsBefore = repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }
        val token = attemptFor(workflowId, "attempt-graft")
        val accepted = results.submit(scope, token, business(goodContinuation), observed("attempt-graft"), null)

        val added = expansion.graft(scope, namespace, workflowId, expanderStep(), "attempt-graft")

        assertThat(added.map { it.id }).containsExactly(approval, "build", "review")
        val run = repository.findInstance(scope, namespace, workflowId)!!
        val recorded = run.instance["expansion"] as Map<*, *>
        assertThat(recorded["stepIds"]).isEqualTo(listOf(approval, "build", "review"))
        assertThat(recorded["sourceStepId"]).isEqualTo(expander)
        assertThat(recorded.containsKey("agentsVerified")).isFalse()
        val projected = (run.projection["steps"] as List<*>).filterIsInstance<Map<*, *>>().associateBy { it["id"] }
        assertThat(projected.keys).containsExactly(expander, approval, "build", "review")
        assertThat(projected.getValue(approval)["dependsOn"]).isEqualTo(listOf(expander))
        assertThat((projected.getValue(approval)["responsibility"] as Map<*, *>)["kind"]).isEqualTo("human")
        assertThat(projected.getValue("build")["dependsOn"]).isEqualTo(listOf(approval))
        assertThat(projected.getValue("review")["dependsOn"]).isEqualTo(listOf("build"))
        assertThat(projected.getValue("build")["status"]).isEqualTo(WorkflowStatuses.PENDING)
        assertThat(run.projection["expansion"]).isNotNull()
        assertThat(repository.findStepStates(scope, namespace, workflowId).map { it.stepId }).contains(approval, "build", "review")
        assertThat(evidenceCount(workflowId)).isEqualTo(1)

        // The catalog is untouched; the run follows base + added steps through the single resolver.
        assertThat(repository.listDefinitions(scope).map { it.workflowType to it.definitionHash }).isEqualTo(definitionsBefore)
        assertThat(resolverBean.resolve(scope, run).steps.map { it.id }).containsExactly(expander, approval, "build", "review")

        // Idempotent: replaying the same accepted result adds nothing, at the graft and at the submission.
        assertThat(expansion.graft(scope, namespace, workflowId, expanderStep(), "attempt-graft")).isEmpty()
        val replay = results.submit(scope, token, business(goodContinuation), observed("attempt-graft"), null)
        assertThat(replay.idempotent).isTrue()
        assertThat(replay.resultId).isEqualTo(accepted.resultId)
        assertThat(repository.findInstance(scope, namespace, workflowId)!!.revision).isEqualTo(run.revision)
        assertThat(evidenceCount(workflowId)).isEqualTo(1)
    }

    @Test
    fun `a run without an expansion keeps resolving to its base definition`() {
        val run = startRun("expansion-none")

        assertThat(resolverBean.resolve(scope, run).steps.map { it.id }).containsExactly(expander)
        assertThatThrownBy { expansion.expandedDefinitionDraft(scope, namespace, "expansion-none") }
            .isInstanceOf(WorkflowException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", "WORKFLOW_NOT_EXPANDED")
    }

    @Test
    fun `the draft of an expanded run keeps the approval step, validates, drops the expansion declaration and registers nothing`() {
        val workflowId = "expansion-draft"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-draft")
        results.submit(scope, token, business(goodContinuation), observed("attempt-draft"), null)
        expansion.graft(scope, namespace, workflowId, expanderStep(), "attempt-draft")
        val definitionsBefore = repository.listDefinitions(scope).size

        val draft = expansion.expandedDefinitionDraft(scope, namespace, workflowId)

        @Suppress("UNCHECKED_CAST")
        val definition = draft["definition"] as Map<String, Any?>
        assertThat(WorkflowDefinitionValidator.validate(definition)).isInstanceOf(WorkflowDefinitionValidation.Valid::class.java)
        val steps = (definition["steps"] as List<*>).filterIsInstance<Map<*, *>>()
        assertThat(steps.map { it["id"] }).containsExactly(expander, approval, "build", "review")
        assertThat(steps.none { it.containsKey("expansion") }).isTrue()
        assertThat(definition["workflowType"]).isNotEqualTo("architect-custom-flow")
        assertThat((draft["source"] as Map<*, *>)["workflowType"]).isEqualTo("architect-custom-flow")
        assertThat(repository.listDefinitions(scope)).hasSize(definitionsBefore)
    }

    @Test
    fun `an altered effective definition hash fails loudly for the sequencer and leniently for a result submission`() {
        val workflowId = "expansion-tamper"
        startRun(workflowId)
        val token = attemptFor(workflowId, "attempt-tamper")
        results.submit(scope, token, business(goodContinuation), observed("attempt-tamper"), null)
        expansion.graft(scope, namespace, workflowId, expanderStep(), "attempt-tamper")
        val run = repository.findInstance(scope, namespace, workflowId)!!
        val tamperedInstance = run.instance.toMutableMap()
        tamperedInstance["expansion"] = (run.instance["expansion"] as Map<*, *>).entries
            .associate { it.key.toString() to it.value } + ("effectiveDefinitionHash" to "0".repeat(64))
        val tampered = run.copy(revision = run.revision + 1, instance = tamperedInstance)
        assertThat(repository.updateInstance(scope, namespace, workflowId, run.revision, tampered)).isTrue()
        val stored = repository.findInstance(scope, namespace, workflowId)!!

        // Strict (sequencer): the run must fail loudly, never fall back to its base definition.
        assertThatThrownBy { resolverBean.resolve(scope, stored) }
            .isInstanceOf(WorkflowException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkflowErrorCodes.WORKFLOW_DEFINITION_MISMATCH)
        // Lenient (result submission): behaves as before expansions existed, no new 409 failure mode.
        assertThat(resolverBean.resolveRecordOrNull(scope, stored)).isNull()
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
