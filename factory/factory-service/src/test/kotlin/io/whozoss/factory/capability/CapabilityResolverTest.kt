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

    @Test
    fun `build keeps the before HEAD and rejects a changed HEAD even when command passes`() {
        val git = Files.createDirectories(repoRoot.resolve(".git"))
        val before = "a".repeat(40)
        val after = "b".repeat(40)
        Files.writeString(git.resolve("HEAD"), before)
        writeManifest("build", "printf '$after' > .git/HEAD")

        val run = resolver.resolve(step(ResponsibilityKind.CODE, "build"), repoRoot) as CapabilityOutcome.CodeExecuted
        assertThat(run.observedHead).isEqualTo(before)
        assertThat(run.observedHeadAfter).isEqualTo(after)
        assertThat(run.exitCode).isZero()
        assertThat(run.workspaceStable).isFalse()
        assertThat(run.verdict).isFalse()
    }

    @Test
    fun `linked worktree reports common ref HEAD before and after build`() {
        val gitDir = Files.createDirectories(repoRoot.resolve("metadata/worktree"))
        val common = Files.createDirectories(repoRoot.resolve("metadata/common/refs/heads"))
        val head = "c".repeat(40)
        Files.writeString(repoRoot.resolve(".git"), "gitdir: metadata/worktree")
        Files.writeString(gitDir.resolve("commondir"), "../common")
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/test")
        Files.writeString(common.resolve("test"), head)
        writeManifest("build", "exit 0")
        val run = resolver.resolve(step(ResponsibilityKind.CODE, "build"), repoRoot) as CapabilityOutcome.CodeExecuted
        assertThat(run.observedHead).isEqualTo(head)
        assertThat(run.observedHeadAfter).isEqualTo(head)
        assertThat(run.verdict).isTrue()
    }

    @Test
    fun `build timeout is never a pass`() {
        writeManifest("build", "sleep 2", 20)
        val run = resolver.resolve(step(ResponsibilityKind.CODE, "build"), repoRoot) as CapabilityOutcome.CodeExecuted
        assertThat(run.timedOut).isTrue()
        assertThat(run.verdict).isFalse()
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

    @Test
    fun `prepare work branch normalizes Jira and GitHub issue tickets into work forge branch names`() {
        val capturedBranches = mutableListOf<String>()
        val branchPreparation = io.mockk.mockk<FactoryBranchPreparation>()
        io.mockk.every { branchPreparation.prepare(any(), any(), capture(capturedBranches)) } answers {
            val branch = thirdArg<String>()
            BranchPreparationResult.Prepared(
                io.whozoss.factory.environment.port.EnvironmentGitFacts(
                    providerId = "local-worktree",
                    environmentId = "env-1",
                    environmentRevision = 1,
                    branch = branch,
                    previousBranch = "main",
                    startingHead = "a".repeat(40),
                    baselineHash = "sha256:base",
                    mutationApplied = true,
                    failureStage = "branch_created_or_switched",
                ),
            )
        }
        val branchResolver = CapabilityResolver(branchPreparation = branchPreparation)
        val scope = io.whozoss.factory.persistence.TenantScope("org", "ws")
        val prepStep = step(ResponsibilityKind.CODE, PREPARE_WORK_BRANCH, "prep")

        branchResolver.resolve(prepStep, repoRoot, workflowId = "wf-1", trustedTicket = "#1731", tenantScope = scope)
        branchResolver.resolve(prepStep, repoRoot, workflowId = "wf-1", trustedTicket = "whoz-oss/coday#1731", tenantScope = scope)
        branchResolver.resolve(prepStep, repoRoot, workflowId = "wf-1", trustedTicket = "WZ-33157", tenantScope = scope)
        branchResolver.resolve(prepStep, repoRoot, workflowId = "wf-1", trustedTicket = "untrusted ticket", tenantScope = scope)

        assertThat(capturedBranches).containsExactly(
            "work/forge/1731",
            "work/forge/1731",
            "work/forge/WZ-33157",
            "work/forge/wf-1",
        )
    }
}
