package io.whozoss.factory.capability

import io.whozoss.factory.verification.manifest.TargetRepoVerifications
import io.whozoss.factory.verification.manifest.VerificationManifestException
import io.whozoss.factory.verification.manifest.VerificationRunResult
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import java.nio.file.Path
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
) {

    fun resolve(
        step: WorkflowStepDefinition,
        repoRoot: Path,
        namespaceId: String? = null,
        workflowId: String? = null,
        brief: String? = null,
    ): CapabilityOutcome =
        when (step.responsibility.kind) {
            ResponsibilityKind.CODE -> resolveCode(step, repoRoot)
            ResponsibilityKind.AGENT -> resolveAgent(step, repoRoot, namespaceId, workflowId, brief)
            ResponsibilityKind.HUMAN ->
                CapabilityOutcome.HumanCheckpointRequired(step.id, step.responsibility.name)
        }

    private fun resolveCode(step: WorkflowStepDefinition, repoRoot: Path): CapabilityOutcome {
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
            )
        }
    }

    private fun resolveAgent(
        step: WorkflowStepDefinition,
        repoRoot: Path,
        namespaceId: String?,
        workflowId: String?,
        brief: String?,
    ): CapabilityOutcome {
        val result = agentTurnCapability.executeAgentTurn(
            AgentTurnRequest(
                stepId = step.id,
                persona = step.responsibility.name,
                repoRoot = repoRoot,
                namespaceId = namespaceId,
                workflowId = workflowId,
                brief = brief,
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
}
