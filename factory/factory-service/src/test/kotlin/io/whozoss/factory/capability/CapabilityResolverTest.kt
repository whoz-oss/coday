package io.whozoss.factory.capability

import io.whozoss.factory.verification.manifest.DEFAULT_VERIFICATION_TIMEOUT_MS
import io.whozoss.factory.verification.manifest.VERIFICATION_MANIFEST_MISSING
import io.whozoss.factory.verification.manifest.VERIFICATION_NOT_DECLARED
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Pure unit tests of [CapabilityResolver] (no Spring, no database).
 *
 * The `code` branch is exercised end-to-end against a temporary destination
 * repository: a real `factory/verification.json` whitelist and executable shell
 * fixtures. The `agent` branch is exercised through a fake
 * [AgentTurnCapability], and the `human` branch returns its checkpoint intent.
 */
class CapabilityResolverTest {

    @TempDir
    lateinit var repoRoot: Path

    private val resolver = CapabilityResolver()

    private fun step(kind: ResponsibilityKind, name: String, id: String = "step-1"): WorkflowStepDefinition =
        WorkflowStepDefinition(
            id = id,
            name = "Step $id",
            responsibility = WorkflowStepResponsibility(kind, name),
            dependsOn = emptyList(),
        )

    private fun writeManifest(name: String, command: String, timeoutMs: Long? = null) {
        val dir = repoRoot.resolve("factory")
        Files.createDirectories(dir)
        val timeout = if (timeoutMs == null) "" else ", \"timeoutMs\": $timeoutMs"
        Files.writeString(
            dir.resolve("verification.json"),
            """{ "schemaVersion": "1", "verifications": { "$name": { "command": "$command"$timeout } } }""",
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

    // ------------------------------------------------------------- code branch

    @Test
    fun `code step executes a declared verification and yields a pass verdict`() {
        writeScript("pass", "exit 0")
        writeManifest("smoke", "./factory/verification/pass")

        val outcome = resolver.resolve(step(ResponsibilityKind.CODE, "smoke"), repoRoot)

        assertThat(outcome).isInstanceOf(CapabilityOutcome.CodeExecuted::class.java)
        val executed = outcome as CapabilityOutcome.CodeExecuted
        assertThat(executed.verificationName).isEqualTo("smoke")
        assertThat(executed.exitCode).isEqualTo(0)
        assertThat(executed.verdict).isTrue()
        assertThat(executed.timeoutMs).isEqualTo(DEFAULT_VERIFICATION_TIMEOUT_MS)
    }

    @Test
    fun `code step yields a fail verdict on a non-zero exit code`() {
        writeScript("fail", "exit 4")
        writeManifest("smoke", "./factory/verification/fail")

        val executed = resolver.resolve(step(ResponsibilityKind.CODE, "smoke"), repoRoot)
                as CapabilityOutcome.CodeExecuted
        assertThat(executed.exitCode).isEqualTo(4)
        assertThat(executed.verdict).isFalse()
    }

    @Test
    fun `code step referencing an undeclared name is refused without executing anything`() {
        writeManifest("smoke", "./factory/verification/pass")
        val marker = repoRoot.resolve("executed.marker")
        writeScript("undeclared", "touch '${marker.toAbsolutePath()}'")

        val outcome = resolver.resolve(step(ResponsibilityKind.CODE, "undeclared"), repoRoot)

        assertThat(outcome).isInstanceOf(CapabilityOutcome.CodeRefused::class.java)
        assertThat((outcome as CapabilityOutcome.CodeRefused).code).isEqualTo(VERIFICATION_NOT_DECLARED)
        assertThat(Files.exists(marker)).isFalse()
    }

    @Test
    fun `code step against a repository without a manifest is refused`() {
        val outcome = resolver.resolve(step(ResponsibilityKind.CODE, "smoke"), repoRoot)
        assertThat((outcome as CapabilityOutcome.CodeRefused).code).isEqualTo(VERIFICATION_MANIFEST_MISSING)
    }

    // ------------------------------------------------------------ agent branch

    @Test
    fun `agent step is deferred as not implemented yet by default`() {
        val outcome = resolver.resolve(step(ResponsibilityKind.AGENT, "architect"), repoRoot)
        assertThat(outcome).isInstanceOf(CapabilityOutcome.AgentDeferred::class.java)
        assertThat((outcome as CapabilityOutcome.AgentDeferred).code).isEqualTo("NOT_IMPLEMENTED_YET")
        assertThat(outcome.persona).isEqualTo("architect")
    }

    @Test
    fun `agent step routes to the capability when it is implemented`() {
        val resolver = CapabilityResolver(
            object : AgentTurnCapability {
                override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult =
                    AgentTurnResult.Completed("finished", mapOf("turns" to 1))
            },
        )

        val outcome = resolver.resolve(step(ResponsibilityKind.AGENT, "architect"), repoRoot)

        assertThat(outcome).isInstanceOf(CapabilityOutcome.AgentCompleted::class.java)
        assertThat((outcome as CapabilityOutcome.AgentCompleted).status).isEqualTo("finished")
        assertThat(outcome.facts).containsEntry("turns", 1)
    }

    @Test
    fun `agent step forwards the attempt capability facts to the turn request`() {
        val captured = mutableListOf<AgentTurnRequest>()
        val resolver = CapabilityResolver(
            object : AgentTurnCapability {
                override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
                    captured += request
                    return AgentTurnResult.Completed("PASS")
                }
            },
        )

        resolver.resolve(
            step(ResponsibilityKind.AGENT, "architect"),
            repoRoot,
            namespaceId = "ns-1",
            workflowId = "wf-1",
            brief = "do it",
            attemptId = "attempt-1",
            capabilityToken = "clear-token",
            caseId = "case-1",
        )

        val request = captured.single()
        assertThat(request.attemptId).isEqualTo("attempt-1")
        assertThat(request.capabilityToken).isEqualTo("clear-token")
        assertThat(request.caseId).isEqualTo("case-1")
        assertThat(request.namespaceId).isEqualTo("ns-1")
        assertThat(request.workflowId).isEqualTo("wf-1")
    }

    // ------------------------------------------------------------ human branch

    @Test
    fun `human step yields a checkpoint requirement`() {
        val outcome = resolver.resolve(step(ResponsibilityKind.HUMAN, "reviewer"), repoRoot)
        assertThat(outcome).isInstanceOf(CapabilityOutcome.HumanCheckpointRequired::class.java)
        assertThat((outcome as CapabilityOutcome.HumanCheckpointRequired).role).isEqualTo("reviewer")
    }
}
