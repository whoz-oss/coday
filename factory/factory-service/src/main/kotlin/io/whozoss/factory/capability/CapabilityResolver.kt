package io.whozoss.factory.capability

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.verification.manifest.TargetRepoVerifications
import io.whozoss.factory.verification.manifest.VerificationManifestException
import io.whozoss.factory.verification.manifest.VerificationRunResult
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.springframework.stereotype.Component

/**
 * Routes a declarative session step to the capability that resolves it, by
 * `(kind, name)`:
 *
 *  - `code`  -> loads the destination repository whitelist
 *    (`factory/verification.json`), refuses any name that is not declared
 *    (`VERIFICATION_NOT_DECLARED`) and executes the declared command through
 *    `factory-verification-core` (`OracleExecutor`). The verdict is
 *    `exitCode == 0`; nothing is inferred from the output.
 *  - `agent` -> delegates to [AgentTurnCapability]. W8.3 wires the HTTP AgentOS
 *    implementation ([AgentOsAgentTurnCapability]); the default
 *    [NoOpAgentTurnCapability] is only used by pure unit tests.
 *  - `human` -> returns a checkpoint intent; the persistence boundary
 *    (`human_interactions`) is handled by [CapabilityExecutionService].
 *
 * The resolver itself is pure with respect to the database: it never reads a
 * development verification name — the core knows none, it only reads the target
 * repository manifest.
 */
@Component
class CapabilityResolver(
    private val agentTurnCapability: AgentTurnCapability = NoOpAgentTurnCapability(),
    private val frontVerificationRegistry: FactoryFrontVerificationRegistry? = null,
    private val branchPreparation: FactoryBranchPreparation? = null,
) {

    fun resolve(
        step: WorkflowStepDefinition,
        repoRoot: Path,
        namespaceId: String? = null,
        workflowId: String? = null,
        brief: String? = null,
        attemptId: String? = null,
        capabilityToken: String? = null,
        caseId: String? = null,
        modifiedFiles: List<String> = emptyList(),
        trustedTicket: String? = null,
        tenantScope: TenantScope? = null,
    ): CapabilityOutcome =
        when (step.responsibility.kind) {
            ResponsibilityKind.CODE -> resolveCode(step, repoRoot, workflowId, modifiedFiles, trustedTicket, tenantScope)
            ResponsibilityKind.AGENT -> resolveAgent(step, repoRoot, namespaceId, workflowId, brief, attemptId, capabilityToken, caseId)
            ResponsibilityKind.HUMAN ->
                CapabilityOutcome.HumanCheckpointRequired(step.id, step.responsibility.name)
        }

    private fun resolveCode(
        step: WorkflowStepDefinition,
        repoRoot: Path,
        workflowId: String?,
        modifiedFiles: List<String>,
        trustedTicket: String?,
        tenantScope: TenantScope?,
    ): CapabilityOutcome {
        if (step.responsibility.name == PREPARE_WORK_BRANCH) {
            val preparation = branchPreparation
                ?: return CapabilityOutcome.CodeRefused(step.id, PREPARE_WORK_BRANCH, BRANCH_PREPARATION_FAILED, "Branch preparation control plane is unavailable.")
            val stableWorkflowId = workflowId
                ?: return CapabilityOutcome.CodeRefused(step.id, PREPARE_WORK_BRANCH, BRANCH_PREPARATION_FAILED, "Workflow identity is required for branch preparation.")
            val scope = tenantScope
                ?: return CapabilityOutcome.CodeRefused(step.id, PREPARE_WORK_BRANCH, BRANCH_PREPARATION_FAILED, "Tenant scope is required for environment binding.")
            val branchComponent = sanitizeBranchComponent(trustedTicket?.takeIf { TRUSTED_TICKET.matches(it) } ?: stableWorkflowId)
            val branchName = "factory/$branchComponent"
            return when (val result = preparation.prepare(scope, stableWorkflowId, branchName)) {
                is BranchPreparationResult.Prepared -> result.facts.let { facts -> CapabilityOutcome.BranchPrepared(
                    step.id, facts.branch ?: branchName, facts.startingHead ?: "", "environment:${facts.environmentId}",
                    "environment:${facts.environmentId}:${facts.environmentRevision}", facts.baselineHash ?: "", facts.baselinePathCount ?: 0,
                    facts.dirty ?: false, facts.baselinePaths, facts.previousBranch, facts.mutationApplied,
                    facts.providerId, facts.environmentId, facts.environmentRevision,
                ) }
                is BranchPreparationResult.Refused -> result.facts.let { facts -> CapabilityOutcome.CodeRefused(
                    step.id, PREPARE_WORK_BRANCH, BRANCH_PREPARATION_FAILED, facts.message ?: "Environment provider refused branch preparation.",
                    facts.branch, facts.previousBranch, facts.mutationApplied, facts.failureStage,
                    facts.providerId, facts.environmentId, facts.environmentRevision,
                ) }
            }
        }
        when (val central = frontVerificationRegistry?.resolve(step.responsibility.name ?: "", repoRoot, modifiedFiles)) {
            is FactoryFrontVerificationResolution.Executed -> return central.run.toOutcome(step.id, repoRoot)
            is FactoryFrontVerificationResolution.Refused -> return CapabilityOutcome.CodeRefused(
                stepId = step.id,
                verificationName = step.responsibility.name ?: "",
                code = central.code,
                message = central.message,
            )
            FactoryFrontVerificationResolution.NotFactoryOwned, null -> Unit
        }
        val target = try {
            TargetRepoVerifications.load(repoRoot)
        } catch (e: VerificationManifestException) {
            return CapabilityOutcome.CodeRefused(
                stepId = step.id,
                verificationName = step.responsibility.name,
                code = e.code,
                message = e.message ?: e.code,
            )
        }
        return when (val run = target.execute(step.responsibility.name)) {
            is VerificationRunResult.NotDeclared -> CapabilityOutcome.CodeRefused(
                stepId = step.id,
                verificationName = step.responsibility.name,
                code = run.code,
                message = "Verification '${run.name}' is not declared in factory/verification.json.",
            )
            is VerificationRunResult.Executed -> CapabilityOutcome.CodeExecuted(
                stepId = step.id,
                verificationName = run.name,
                command = run.command,
                timeoutMs = run.timeoutMs,
                exitCode = run.exitCode,
                timedOut = run.timedOut,
                durationMs = run.durationMs,
                stdout = run.stdout,
                stderr = run.stderr,
                cwd = observedRoot(repoRoot),
                observedHead = observedGitHead(repoRoot),
                rootIdentity = rootIdentity(repoRoot),
            )
        }
    }

    private fun VerificationRunResult.Executed.toOutcome(stepId: String, repoRoot: Path) = CapabilityOutcome.CodeExecuted(
        stepId = stepId,
        verificationName = name,
        command = command,
        timeoutMs = timeoutMs,
        exitCode = exitCode,
        timedOut = timedOut,
        durationMs = durationMs,
        stdout = stdout,
        stderr = stderr,
        cwd = observedRoot(repoRoot),
        observedHead = observedGitHead(repoRoot),
        rootIdentity = rootIdentity(repoRoot),
    )

    private fun observedRoot(repoRoot: Path): String =
        runCatching { repoRoot.toRealPath().toString() }.getOrElse { repoRoot.toAbsolutePath().normalize().toString() }

    private fun rootIdentity(repoRoot: Path): String {
        val root = observedRoot(repoRoot)
        val digest = MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Reads HEAD without invoking Git or mutating the worktree. Supports normal repos and linked worktrees. */
    private fun observedGitHead(repoRoot: Path): String? = runCatching {
        val dotGit = repoRoot.toAbsolutePath().normalize().resolve(".git")
        val gitDir = when {
            Files.isDirectory(dotGit) -> dotGit
            Files.isRegularFile(dotGit) -> {
                val line = Files.readString(dotGit).lineSequence().firstOrNull()?.trim().orEmpty()
                val raw = line.removePrefix("gitdir:").trim()
                if (raw.isEmpty()) return@runCatching null
                val path = Path.of(raw)
                if (path.isAbsolute) path.normalize() else repoRoot.resolve(path).normalize()
            }
            else -> return@runCatching null
        }
        val head = Files.readString(gitDir.resolve("HEAD")).trim()
        if (!head.startsWith("ref:")) return@runCatching head.takeIf { GIT_SHA.matches(it) }
        val ref = head.removePrefix("ref:").trim()
        val loose = gitDir.resolve(ref)
        if (Files.isRegularFile(loose)) return@runCatching Files.readString(loose).trim().takeIf { GIT_SHA.matches(it) }
        val commonDir = gitDir.resolve("commondir").takeIf(Files::isRegularFile)?.let {
            val path = Path.of(Files.readString(it).trim())
            if (path.isAbsolute) path else gitDir.resolve(path).normalize()
        } ?: gitDir
        val commonLoose = commonDir.resolve(ref)
        if (Files.isRegularFile(commonLoose)) return@runCatching Files.readString(commonLoose).trim().takeIf { GIT_SHA.matches(it) }
        Files.readAllLines(commonDir.resolve("packed-refs")).asSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith(" $ref") }
            ?.substringBefore(' ')
            ?.takeIf { GIT_SHA.matches(it) }
    }.getOrNull()

    private fun resolveAgent(
        step: WorkflowStepDefinition,
        repoRoot: Path,
        namespaceId: String?,
        workflowId: String?,
        brief: String?,
        attemptId: String?,
        capabilityToken: String?,
        caseId: String?,
    ): CapabilityOutcome {
        val result = agentTurnCapability.executeAgentTurn(
            AgentTurnRequest(
                stepId = step.id,
                persona = step.responsibility.name,
                repoRoot = repoRoot,
                namespaceId = namespaceId,
                workflowId = workflowId,
                brief = brief,
                attemptId = attemptId,
                capabilityToken = capabilityToken,
                caseId = caseId,
            ),
        )
        return when (result) {
            is AgentTurnResult.NotImplementedYet -> CapabilityOutcome.AgentDeferred(
                stepId = step.id,
                persona = step.responsibility.name,
                code = AGENT_NOT_IMPLEMENTED_YET,
                message = result.reason,
            )
            is AgentTurnResult.Completed -> CapabilityOutcome.AgentCompleted(
                stepId = step.id,
                persona = step.responsibility.name,
                status = result.status,
                facts = result.facts,
            )
            is AgentTurnResult.Failed -> CapabilityOutcome.AgentFailed(
                stepId = step.id,
                persona = step.responsibility.name,
                code = result.code,
                message = result.message,
                facts = result.facts,
            )
        }
    }

    private fun sanitizeBranchComponent(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.').take(100)

    private companion object {
        val TRUSTED_TICKET = Regex("^[A-Z][A-Z0-9]{1,15}-[0-9]{1,12}$")
        val GIT_SHA = Regex("^[0-9a-fA-F]{40}$")
    }
}
