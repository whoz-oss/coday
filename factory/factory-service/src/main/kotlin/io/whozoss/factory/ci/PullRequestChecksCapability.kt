package io.whozoss.factory.ci

import com.fasterxml.jackson.databind.JsonNode
import io.whozoss.factory.capability.CapabilityOutcome
import io.whozoss.factory.capability.GITHUB_PR_CHECKS
import io.whozoss.factory.verification.redaction.SecretRedaction
import java.nio.file.Path
import org.springframework.stereotype.Component

/**
 * Deterministic CI-check capability: reads the GitHub checks of the pull request opened for the run branch and
 * answers PASS / FAIL / still-pending WITHOUT any LLM call and without spawning a process.
 *
 * Only a real CI verdict is a [CapabilityOutcome.CiChecksCompleted]. Everything else is either
 * [CapabilityOutcome.ExternalPending] (the condition may settle: checks still running or not conclusive, head not
 * pushed yet, transient or rate-limited GitHub) or [CapabilityOutcome.CodeRefused] (missing configuration, no PR,
 * draft PR, repository not allowed, auth error): a refusal blocks the step and never triggers a correction return.
 *
 * Verdict rules, on the LATEST run of each check name (a rerun replaces an older run):
 *  - red: `failure`, `timed_out`, `startup_failure`, or a commit status `failure` / `error`;
 *  - not a verdict: `cancelled`, `stale`, `action_required` (or an unknown conclusion): the step keeps polling
 *    (`CI_CHECKS_NOT_CONCLUSIVE`) so a rerun or a newer push can settle it;
 *  - green: `success`, `neutral`, `skipped` and a commit status `success`.
 */
@Component
class PullRequestChecksCapability(
    private val properties: GitHubChecksProperties,
    private val client: GitHubChecksClient,
) {

    fun check(stepId: String, repoRoot: Path): CapabilityOutcome {
        if (!properties.configured) {
            return refused(
                stepId, "CI_GITHUB_NOT_CONFIGURED",
                "GitHub token is not configured on Factory (set FACTORY_GITHUB_TOKEN, GITHUB_TOKEN, GH_TOKEN, or authenticate the gh CLI).",
            )
        }
        val fromOrigin = GitWorktreeReader.githubOrigin(repoRoot)
        val (owner, repo) = fromOrigin
            ?: properties.repository.split('/').takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }
            ?: return refused(stepId, "CI_REPOSITORY_UNRESOLVED", "The GitHub repository of the run worktree could not be resolved.")
        val allowed = properties.effectiveAllowedRepositories
        if (allowed.isNotEmpty() && allowed.none { it.equals("$owner/$repo", ignoreCase = true) }) {
            return refused(stepId, "CI_REPOSITORY_NOT_ALLOWED", "The repository of the run worktree is not allowed for CI checks.")
        }
        val branch = GitWorktreeReader.branch(repoRoot)
            ?: return refused(stepId, "CI_BRANCH_UNRESOLVED", "The run worktree has no attached branch.")

        val pullRequest = when (val found = client.findOpenPullRequest(owner, repo, branch)) {
            is GitHubResult.Ok -> found.value ?: return refused(stepId, "CI_PR_NOT_FOUND", "No open pull request exists for branch '$branch'.")
            is GitHubResult.Transient -> return transient(stepId, found)
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
                facts = mapOf("prNumber" to pullRequest.number, "headSha" to pullRequest.headSha, "localHead" to localHead),
            )
        }

        val allRuns = when (val result = client.checkRuns(owner, repo, pullRequest.headSha)) {
            is GitHubResult.Ok -> result.value
            is GitHubResult.Transient -> return transient(stepId, result)
            is GitHubResult.Refused -> return githubRefused(stepId, result)
        }
        val statuses = when (val result = client.statuses(owner, repo, pullRequest.headSha)) {
            is GitHubResult.Ok -> result.value
            is GitHubResult.Transient -> return transient(stepId, result)
            is GitHubResult.Refused -> return githubRefused(stepId, result)
        }
        val runs = latestRunPerName(allRuns)

        val checks = runs.map { run ->
            checkEntry(run.path("name").asText(), run.path("status").asText(), run.path("conclusion").asText(""), run.path("html_url").asText(""))
        } + statuses.map { status ->
            val state = status.path("state").asText()
            checkEntry(status.path("context").asText(), if (state == "pending") "in_progress" else "completed", state, status.path("target_url").asText(""))
        }
        val pendingCount = checks.count { it["status"] != "completed" }
        if (checks.isEmpty() || pendingCount > 0) {
            return pending(
                stepId, "CI_CHECKS_PENDING",
                if (checks.isEmpty()) "No check has been reported for ${pullRequest.headSha.take(SHORT_SHA)} yet." else "$pendingCount of ${checks.size} checks are still running.",
                facts = mapOf("prNumber" to pullRequest.number, "headSha" to pullRequest.headSha, "total" to checks.size, "pending" to pendingCount),
            )
        }

        val redRuns = runs.filter { it.path("conclusion").asText() in RED_CONCLUSIONS }
        val redStatuses = statuses.filter { it.path("state").asText() in RED_STATES }
        if (redRuns.isEmpty() && redStatuses.isEmpty()) {
            val inconclusive = runs.filter { it.path("conclusion").asText() !in GREEN_CONCLUSIONS }
            if (inconclusive.isNotEmpty()) {
                return pending(
                    stepId, "CI_CHECKS_NOT_CONCLUSIVE",
                    "${inconclusive.size} checks have no verdict (${inconclusive.map { it.path("conclusion").asText() }.distinct().joinToString()}): waiting for a rerun or a new push.",
                    facts = mapOf("prNumber" to pullRequest.number, "headSha" to pullRequest.headSha, "inconclusive" to inconclusive.map { it.path("name").asText() }),
                )
            }
        }

        val deadlineNanos = System.nanoTime() + properties.pollBudgetMs * NANOS_PER_MILLI
        val failedChecks = redRuns.mapIndexed { index, run ->
            val withLog = index < properties.maxFailedJobsWithLogs && System.nanoTime() < deadlineNanos
            failedRun(owner, repo, run, withLog)
        } + redStatuses.map { status ->
            mapOf<String, Any?>(
                "name" to status.path("context").asText(), "conclusion" to status.path("state").asText(),
                "url" to status.path("target_url").asText(""), "summary" to SecretRedaction.redact(status.path("description").asText("")),
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

    /** A rerun replaces an older run of the same name: keep the highest `started_at`, then the highest id. */
    private fun latestRunPerName(runs: List<JsonNode>): List<JsonNode> =
        runs.groupBy { it.path("name").asText() }.values.map { sameName ->
            sameName.maxWith(compareBy<JsonNode> { it.path("started_at").asText("") }.thenBy { it.path("id").asLong(0) })
        }

    /** A failed check run with its summary and, for a GitHub Actions job, the redacted tail of its log. */
    private fun failedRun(owner: String, repo: String, run: JsonNode, withLog: Boolean): Map<String, Any?> {
        val entry = linkedMapOf<String, Any?>(
            "name" to run.path("name").asText(),
            "conclusion" to run.path("conclusion").asText(),
            "url" to run.path("html_url").asText(""),
            "summary" to SecretRedaction.redact(run.path("output").path("summary").asText("")).take(SUMMARY_CHARS),
        )
        if (withLog && run.path("app").path("slug").asText() == "github-actions" && run.path("id").canConvertToLong()) {
            when (val log = client.jobLogTail(owner, repo, run.path("id").asLong(), properties.logExcerptBytes)) {
                is GitHubResult.Ok -> {
                    // Redaction runs AFTER the tail was trimmed to a line boundary, so no secret is cut in two.
                    entry["logExcerpt"] = SecretRedaction.redact(log.value.text)
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

    private fun pending(
        stepId: String,
        reason: String,
        summary: String,
        retryAfterSeconds: Int? = null,
        facts: Map<String, Any?> = emptyMap(),
    ) = CapabilityOutcome.ExternalPending(
        stepId, source = "ci-checks", reason = reason, summary = summary,
        retryAfterSeconds = retryAfterSeconds, facts = facts,
    )

    /** A transient or rate-limited GitHub answer: poll again, after the delay GitHub asked for when it said so. */
    private fun transient(stepId: String, result: GitHubResult.Transient) = pending(
        stepId, "CI_GITHUB_TRANSIENT: ${result.reason}", "GitHub is temporarily unavailable.",
        retryAfterSeconds = result.retryAfterSeconds,
    )

    private fun refused(stepId: String, code: String, message: String) =
        CapabilityOutcome.CodeRefused(stepId, GITHUB_PR_CHECKS, code, message)

    private fun githubRefused(stepId: String, refusal: GitHubResult.Refused) =
        if (refusal.code == 0) {
            refused(stepId, "CI_GITHUB_UNTRUSTED_URL", "GitHub returned a URL outside the configured API origin; it was not followed.")
        } else {
            refused(stepId, "CI_GITHUB_REFUSED", "GitHub refused the request (HTTP ${refusal.code}).")
        }

    private companion object {
        const val SHORT_SHA = 8
        const val MAX_CHECKS = 100
        const val SUMMARY_CHARS = 2_000
        const val NANOS_PER_MILLI = 1_000_000L
        val RED_CONCLUSIONS = setOf("failure", "timed_out", "startup_failure")
        val GREEN_CONCLUSIONS = setOf("success", "neutral", "skipped")
        val RED_STATES = setOf("failure", "error")
    }
}
