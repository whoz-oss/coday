package io.whozoss.factory.capability

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.verification.manifest.VERIFICATION_MANIFEST_MISSING
import io.whozoss.factory.verification.manifest.VERIFICATION_NOT_DECLARED
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired

/**
 * Integration tests of the capability resolution persistence boundary (W8.2).
 *
 * Extends the shared [DomainIntegrationTest] fixture (one Spring context, one
 * Testcontainers PostgreSQL) — no new `@SpringBootTest` variant. Verifies that a
 * `code` step executes the destination-repo verification and records verdict +
 * evidence, that undeclared names are refused without execution, that an `agent`
 * step is `NOT_IMPLEMENTED_YET`, and that a `human` step opens a waiting
 * checkpoint.
 */
class CapabilityExecutionIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var service: CapabilityExecutionService

    @Autowired
    private lateinit var workflowRepository: WorkflowRepository

    @Autowired
    private lateinit var evidenceRepository: WorkflowEvidenceRepository

    @Autowired
    private lateinit var interactionRepository: HumanInteractionRepository

    @TempDir
    lateinit var repoRoot: Path

    private val namespace = "2b6f8d2f-8d1a-4f0e-9b6e-capability-w82"
    private val workflowId = "wf-capability-w82"

    private val tenantScope: TenantScope get() = scope

    private fun createInstance() {
        workflowRepository.insertInstance(
            tenantScope,
            WorkflowInstanceRecord(
                namespaceId = namespace,
                workflowId = workflowId,
                revision = 1,
                status = "active",
                creationCommandHash = "capability-w82-$workflowId",
                instance = mapOf("workflowId" to workflowId),
                projection = emptyMap(),
            ),
        )
    }

    private fun step(kind: ResponsibilityKind, name: String, id: String = "step-1"): WorkflowStepDefinition =
        WorkflowStepDefinition(
            id = id,
            name = "Step $id",
            responsibility = WorkflowStepResponsibility(kind, name),
            dependsOn = emptyList(),
        )

    private fun writeManifest(name: String, command: String) {
        val dir = repoRoot.resolve("factory")
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve("verification.json"),
            """{ "schemaVersion": "1", "verifications": { "$name": { "command": "$command" } } }""",
            StandardCharsets.UTF_8,
        )
    }

    private fun writeScript(name: String, body: String) {
        val dir = repoRoot.resolve("factory/verification")
        Files.createDirectories(dir)
        val script = dir.resolve(name)
        Files.writeString(script, "#!/bin/sh\n$body\n", StandardCharsets.UTF_8)
        check(script.toFile().setExecutable(true)) { "could not mark $script executable" }
    }

    @Test
    fun `a code step executes the declared verification and records verdict and evidence`() {
        createInstance()
        writeScript("pass", "exit 0")
        writeManifest("smoke", "./factory/verification/pass")

        val execution = service.resolveAndRecord(
            tenantScope,
            namespace,
            workflowId,
            step(ResponsibilityKind.CODE, "smoke"),
            repoRoot,
        )

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.CodeExecuted::class.java)
        assertThat((execution.outcome as CapabilityOutcome.CodeExecuted).verdict).isTrue()
        assertThat(execution.codeTransitionId).isNotNull()
        assertThat(execution.evidenceId).isNotNull()

        val transitions = workflowRepository.listCodeTransitions(tenantScope, namespace, workflowId)
        assertThat(transitions).hasSize(1)
        assertThat(transitions.first().outcome).isEqualTo("pass")
        assertThat(transitions.first().exitCode).isEqualTo(0)

        val evidence = evidenceRepository.list(tenantScope, namespace, workflowId)
        assertThat(evidence).hasSize(1)
        assertThat(evidence.first().kind).isEqualTo("code-verification")
        assertThat(evidence.first().outcome).isEqualTo("pass")
        assertThat(evidence.first().facts["verification"]).isEqualTo("smoke")
    }

    @Test
    fun `an undeclared code step is refused and records nothing`() {
        createInstance()
        writeManifest("smoke", "./factory/verification/pass")

        val execution = service.resolveAndRecord(
            tenantScope,
            namespace,
            workflowId,
            step(ResponsibilityKind.CODE, "undeclared"),
            repoRoot,
        )

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.CodeRefused::class.java)
        assertThat((execution.outcome as CapabilityOutcome.CodeRefused).code).isEqualTo(VERIFICATION_NOT_DECLARED)
        assertThat(execution.codeTransitionId).isNull()
        assertThat(workflowRepository.listCodeTransitions(tenantScope, namespace, workflowId)).isEmpty()
        assertThat(evidenceRepository.list(tenantScope, namespace, workflowId)).isEmpty()
    }

    @Test
    fun `a code step without a target manifest is refused`() {
        createInstance()

        val execution = service.resolveAndRecord(
            tenantScope,
            namespace,
            workflowId,
            step(ResponsibilityKind.CODE, "smoke"),
            repoRoot,
        )

        assertThat((execution.outcome as CapabilityOutcome.CodeRefused).code).isEqualTo(VERIFICATION_MANIFEST_MISSING)
    }

    @Test
    fun `an agent step is deferred as not implemented yet`() {
        createInstance()

        val execution = service.resolveAndRecord(
            tenantScope,
            namespace,
            workflowId,
            step(ResponsibilityKind.AGENT, "architect"),
            repoRoot,
        )

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.AgentDeferred::class.java)
        assertThat((execution.outcome as CapabilityOutcome.AgentDeferred).code).isEqualTo("NOT_IMPLEMENTED_YET")
        assertThat(execution.codeTransitionId).isNull()
        assertThat(execution.interactionId).isNull()
    }

    @Test
    fun `a human step opens a waiting checkpoint`() {
        createInstance()

        val execution = service.resolveAndRecord(
            tenantScope,
            namespace,
            workflowId,
            step(ResponsibilityKind.HUMAN, "reviewer"),
            repoRoot,
        )

        assertThat(execution.outcome).isInstanceOf(CapabilityOutcome.HumanCheckpointRequired::class.java)
        assertThat(execution.interactionId).isNotNull()

        val open = interactionRepository.list(tenantScope, namespace, workflowId, openOnly = true)
        assertThat(open).hasSize(1)
        assertThat(open.first().status).isEqualTo("waiting")
        assertThat(open.first().stepId).isEqualTo("step-1")
        assertThat(open.first().payload["role"]).isEqualTo("reviewer")
    }
}
