package io.whozoss.factory.workflow.service

import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.capability.PREPARE_WORK_BRANCH
import io.whozoss.factory.verification.redaction.SecretRedaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Preserve refusal versus execution in durable session evidence with bounded, redacted diagnostics. */
internal fun codeVerificationFacts(outcome: CapabilityOutcome): Map<String, Any?> = when (outcome) {
    is CapabilityOutcome.BranchPrepared -> mapOf(
        "operation" to PREPARE_WORK_BRANCH,
        "executed" to true,
        "branch" to outcome.branch,
        "startingHead" to outcome.startingHead,
        "environmentRoot" to outcome.environmentRoot,
        "rootIdentity" to outcome.rootIdentity,
        "baselineHash" to outcome.baselineHash,
        "baselinePathCount" to outcome.baselinePathCount,
        "baselinePaths" to outcome.baselinePaths,
        "inheritedDirty" to outcome.inheritedDirty,
        "previousBranch" to outcome.previousBranch,
        "mutationApplied" to outcome.mutationApplied,
        "failureStage" to "branch_created_or_switched",
        "provider" to outcome.providerId,
        "environmentId" to outcome.environmentId,
        "environmentRevision" to outcome.environmentRevision,
        "reusedWorktree" to true,
    )
    is CapabilityOutcome.CodeRefused -> mapOf(
        "verification" to outcome.verificationName,
        "executed" to false,
        "cwd" to "target-repository-root",
        "code" to outcome.code,
        "message" to redactAndBound(outcome.message, MAX_OUTPUT_CHARS),
        "branch" to outcome.branch,
        "previousBranch" to outcome.previousBranch,
        "mutationApplied" to outcome.mutationApplied,
        "failureStage" to outcome.failureStage,
        "provider" to outcome.providerId,
        "environmentId" to outcome.environmentId,
        "environmentRevision" to outcome.environmentRevision,
    )
    is CapabilityOutcome.CodeExecuted -> mapOf(
        "verification" to outcome.verificationName,
        "executed" to true,
        "command" to redactAndBound(outcome.command, MAX_COMMAND_CHARS),
        "cwd" to (outcome.cwd ?: "target-repository-root"),
        "observedHead" to outcome.observedHead,
        "observedHeadAfter" to outcome.observedHeadAfter,
        "workspaceStable" to outcome.workspaceStable,
        "rootIdentity" to outcome.rootIdentity,
        "exitCode" to outcome.exitCode,
        "timedOut" to outcome.timedOut,
        "durationMs" to outcome.durationMs,
        "timeoutMs" to outcome.timeoutMs,
        "stdout" to boundedStream(outcome.stdout),
        "stderr" to boundedStream(outcome.stderr),
        "code" to when {
            !outcome.workspaceStable -> "VERIFICATION_WORKSPACE_CHANGED"
            outcome.timedOut -> "VERIFICATION_TIMED_OUT"
            outcome.verdict -> "VERIFICATION_PASSED"
            else -> "VERIFICATION_FAILED"
        },
        "message" to when {
            !outcome.workspaceStable -> "Workspace branch/HEAD changed during verification; build proof rejected."
            outcome.timedOut -> "Verification timed out after ${outcome.durationMs} ms."
            outcome.verdict -> "Verification completed successfully."
            else -> "Verification exited with code ${outcome.exitCode}."
        },
    )
    is CapabilityOutcome.CiChecksCompleted -> mapOf(
        "verification" to "github-pr-checks",
        "executed" to true,
        "code" to if (outcome.passed) "CI_CHECKS_PASSED" else "CI_CHECKS_FAILED",
        "prNumber" to outcome.prNumber,
        "prUrl" to outcome.prUrl,
        "headSha" to outcome.headSha,
        "failedCheckNames" to outcome.failedChecks.mapNotNull { it["name"] },
        "message" to if (outcome.passed) "All checks of pull request #${outcome.prNumber} passed."
        else "Checks failed on pull request #${outcome.prNumber}: ${outcome.failedChecks.mapNotNull { it["name"] }.joinToString()}",
    )
    else -> emptyMap()
}

private fun boundedStream(value: String): Map<String, Any?> {
    val redacted = redactAndBound(value, MAX_OUTPUT_CHARS)
    return mapOf(
        "excerpt" to redacted,
        "truncated" to (value.length > MAX_OUTPUT_CHARS),
        "sha256" to "sha256:${sha256(value)}",
    )
}

private fun redactAndBound(value: String, limit: Int): String = SecretRedaction.redact(value).take(limit)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private const val MAX_OUTPUT_CHARS = 16_384
private const val MAX_COMMAND_CHARS = 2_048
