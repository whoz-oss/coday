package io.whozoss.factory.ci

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.capability.GITHUB_PR_CHECKS
import java.nio.file.Path
import org.springframework.stereotype.Component

/**
 * Deterministic CI-check capability: reads the GitHub checks of the pull request opened for the run branch and
 * answers PASS / FAIL / still-pending WITHOUT any LLM call and without spawning a process.
 *
 * Only a real CI verdict is a [CapabilityOutcome.CiChecksCompleted]. Everything else is either
 * [CapabilityOutcome.ExternalPending] (the condition may settle: checks still running, head not pushed yet,
 * transient GitHub error) or [CapabilityOutcome.CodeRefused] (missing configuration, no PR, draft PR, auth
 * error): a refusal blocks the step and never triggers a correction return.
 */
@Component
class PullRequestChecksCapability(
    private val properties: GitHubChecksProperties,
    private val client: GitHubChecksClient,
) {

    fun check(stepId: String, repoRoot: Path): CapabilityOutcome {
        if (!properties.configured) {
            return refused(stepId, "CI_GITHUB_NOT_CONFIGURED", "FACTORY_GITHUB_TOKEN is not configured on Factory.")
        }
        val (owner, repo) = GitWorktreeReader.githubOrigin(repoRoot)
            ?: properties.repository.split('/').takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }
            ?: return refused(stepId, "CI_REPOSITORY_UNRESOLVED", "The GitHub repository of the run worktree could not be resolved.")
        val branch = GitWorktreeReader.branch(repoRoot)
            ?: return refused(stepId, "CI_BRANCH_UNRESOLVED", "The run worktree has no attached branch.")

        val pullRequest = when (val found = client.findOpenPullRequest(owner, repo, branch)) {
            is GitHubResult.Ok -> found.value ?: return refused(stepId, "CI_PR_NOT_FOUND", "No open pull request exists for branch '$branch'.")
            is GitHubResult.Transient -> return pending(stepId, "CI_GITHUB_TRANSIENT: ${found.reason}", "GitHub is temporarily unavailable.")
            is GitHubResult.Refused -> return githubRefused(stepId, found)
        }
        if (pullRequest.draft) {
            return refused(stepId, "CI_PR_IS_DRAFT", "Pull request #${pullRequest.number} is a draft: its CI is skipped. Mark it ready for review first.")
        }
        val localHead = GitWorktreeReader.head(repoRoot)
        if (localHead != null && !localHead.equals(pullRequest.headSha, ignoreCase = true)) {
            return pending(
                stepId, "CI_HEAD_NOT_PUSHED",
                "The pull request head ${pullRequest.headSha.take(SHORT_SHA)} differs from the local HEAD ${localHead.take(SHORT_SHA)}.",
                mapOf("prNumber" to pullRequest.number, "headSha" to pullRequest.headSha, "localHead" to localHead),
            )
        }

        val runs = when (val result = client.checkRuns(owner, repo, pullRequest.headSha)) {
            is GitHubResult.Ok -> result.value
            is GitHubResult.Transient -> return pending(stepId, "CI_GITHUB_TRANSIENT: ${result.reason}", "GitHub is temporarily unavailable.")
            is GitHubResult.Refused -> return githubRefused(stepId, result)
        }
        val statuses = when (val result = client.combinedStatus(owner, repo, pullRequest.headSha)) {
            is GitHubResult.Ok -> result.value.path("statuses").toList()
            is GitHubResult.Transient -> return pending(stepId, "CI_GITHUB_TRANSIENT: ${result.reason}", "GitHub is temporarily unavailable.")
            is GitHubResult.Refused -> return githubRefused(stepId, result)
        }

        val checks = runs.map { run -> checkEntry(run.path("name").asText(), run.path("status").asText(), run.path("conclusion").asText(""), run.path("html_url").asText("")) } +
            statuses.map { status -> checkEntry(status.path("context").asText(), if (status.path("state").asText() == "pending") "in_progress" else "completed", status.path("state").asText(), status.path("target_url").asText("")) }
        val pendingCount = checks.count { it["status"] != "completed" }
        if (checks.isEmpty() || pendingCount > 0) {
            return pending(
                stepId, "CI_CHECKS_PENDING",
                if (checks.isEmpty()) "No check has been reported for ${pullRequest.headSha.take(SHORT_SHA)} yet." else "$pendingCount of ${checks.size} checks are still running.",
                mapOf("prNumber" to pullRequest.number, "headSha" to pullRequest.headSha, "total" to checks.size, "pending" to pendingCount),
            )
        }

        val failedRuns = runs.filter { it.path("conclusion").asText() in FAILED_CONCLUSIONS }
        val failedStatuses = statuses.filter { it.path("state").asText() in FAILED_STATES }
        val failedChecks = failedRuns.mapIndexed { index, run -> failedRun(owner, repo, run, withLog = index < properties.maxFailedJobsWithLogs) } +
            failedStatuses.map { status ->
                mapOf<String, Any?>(
                    "name" to status.path("context").asText(), "conclusion" to status.path("state").asText(),
                    "url" to status.path("target_url").asText(""), "summary" to redact(status.path("description").asText("")),
                )
            }
        return CapabilityOutcome.CiChecksCompleted(
            stepId = stepId,
            passed = failedChecks.isEmpty(),
            prNumber = pullRequest.number,
            prUrl = pullRequest.url,
            headSha = pullRequest.headSha,
            branch = branch,
            checks = checks.take(MAX_CHECKS),
            failedChecks = failedChecks,
        )
    }

    /** A failed check run with its summary and, for a GitHub Actions job, the redacted tail of its log. */
    private fun failedRun(owner: String, repo: String, run: JsonNode, withLog: Boolean): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "name" to run.path("name").asText(),
            "conclusion" to run.path("conclusion").asText(),
            "url" to run.path("html_url").asText(""),
            "summary" to redact(run.path("output").path("summary").asText("")).take(SUMMARY_CHARS),
        )
        if (withLog && run.path("app").path("slug").asText() == "github-actions" && run.path("id").canConvertToLong()) {
            when (val log = client.jobLogTail(owner, repo, run.path("id").asLong(), properties.logExcerptBytes)) {
                is GitHubResult.Ok -> {
                    entry["logExcerpt"] = redact(log.value.text)
                    entry["logTruncated"] = log.value.truncated
                }
                // Logs are a best effort: the failed check itself is already a sufficient verdict.
                is GitHubResult.Transient, is GitHubResult.Refused -> entry["logExcerpt"] = null
            }
        }
        return entry
    }

    private fun checkEntry(name: String, status: String, conclusion: String, url: String): Map<String, Any?> =
        mapOf("name" to name, "status" to status, "conclusion" to conclusion, "url" to url)

    private fun pending(stepId: String, reason: String, summary: String, facts: Map<String, Any?> = emptyMap()) =
        CapabilityOutcome.ExternalPending(stepId, source = "ci-checks", reason = reason, summary = summary, facts = facts)

    private fun refused(stepId: String, code: String, message: String) =
        CapabilityOutcome.CodeRefused(stepId, GITHUB_PR_CHECKS, code, message)

    private fun githubRefused(stepId: String, refusal: GitHubResult.Refused) =
        refused(stepId, "CI_GITHUB_REFUSED", "GitHub refused the request (HTTP ${refusal.code}).")

    /** Same redaction rules as the session diagnostics: no secret, bearer token or credential assignment survives. */
    private fun redact(value: String): String {
        var result = value
        REDACTIONS.forEach { pattern -> result = pattern.replace(result, "$1=<redacted>") }
        return result
    }

    private companion object {
        const val SHORT_SHA = 8
        const val MAX_CHECKS = 100
        const val SUMMARY_CHARS = 2_000
        val FAILED_CONCLUSIONS = setOf("failure", "timed_out", "cancelled", "action_required", "startup_failure", "stale")
        val FAILED_STATES = setOf("failure", "error")
        val REDACTIONS = listOf(
            Regex("(?i)\\b([A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API_KEY|PRIVATE_KEY))\\s*=\\s*[^\\s]+"),
            Regex("(?i)\\b(authorization)\\s*:\\s*[^\\r\\n]+"),
            Regex("(?i)\\b(bearer)\\s+[^\\s]+"),
        )
    }
}
