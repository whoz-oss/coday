package io.whozoss.agentos.git.core

import com.fasterxml.jackson.databind.JsonNode
import java.net.URLEncoder

/** Read-only, authenticated observation of a historical Whoz Forge deployment. */
class GitHubForgeDeploymentReader(private val api: GitHubApi) {
    data class Evidence(
        val repository: String,
        val workCommit: String,
        val integrationBranch: String,
        val integrationCommit: String,
        val buildRunId: Long,
        val deployRunId: Long,
        val deployJobId: Long,
        val tag: String,
        val target: String,
    )

    /** Find a deployed integration revision without trusting an agent-supplied SHA. */
    fun readLatest(
        repository: GitHubRepository,
        token: String,
        workCommit: String,
        integrationBranch: String,
        target: String,
    ): Evidence? {
        if (!SHA.matches(workCommit) || BRANCH.matchEntire(integrationBranch) == null) return null
        val encoded = URLEncoder.encode(integrationBranch, Charsets.UTF_8)
        val runs = get("repos/${repository.fullName}", "actions/runs?branch=$encoded&per_page=$PAGE_SIZE", token)
            ?: return null
        val all = runs.path("workflow_runs").takeIf { it.isArray }?.toList() ?: return null
        val candidateCommits = all.filter {
            it.path("event").asText() == "workflow_dispatch" &&
                it.path("path").asText() == ".github/workflows/deploy.yml" &&
                it.path("head_branch").asText() == integrationBranch &&
                it.path("conclusion").asText() == "success" &&
                it.path("name").asText() == "Deploy $target"
        }.sortedByDescending { it.path("id").asLong() }
            .map { it.path("head_sha").asText() }.filter(SHA::matches).distinct().take(MAX_CANDIDATES)
        for (commit in candidateCommits) {
            read(repository, token, workCommit, integrationBranch, commit, target)?.let { return it }
        }
        return null
    }

    fun read(
        repository: GitHubRepository,
        token: String,
        workCommit: String,
        integrationBranch: String,
        integrationCommit: String,
        target: String,
    ): Evidence? {
        val workstream = BRANCH.matchEntire(integrationBranch)?.groupValues?.get(1)
        if (!SHA.matches(workCommit) || !SHA.matches(integrationCommit) ||
            workstream == null || target != "forge-$workstream") return null
        val prefix = "repos/${repository.fullName}"
        val compare = get(prefix, "compare/$workCommit...$integrationCommit", token) ?: return null
        if (compare.path("status").asText() !in setOf("ahead", "identical")) return null

        val runs = get(prefix, "actions/runs?head_sha=$integrationCommit&per_page=$PAGE_SIZE", token) ?: return null
        if (runs.path("total_count").asInt(PAGE_SIZE) >= PAGE_SIZE) return null
        val all = runs.path("workflow_runs").takeIf { it.isArray }?.toList() ?: return null
        fun valid(run: JsonNode) = run.path("head_sha").asText() == integrationCommit &&
            run.path("head_branch").asText() == integrationBranch && run.path("conclusion").asText() == "success"
        val build = all.filter { valid(it) && it.path("event").asText() == "push" &&
            it.path("path").asText() == ".github/workflows/build.yml" }
            .maxByOrNull { it.path("id").asLong() } ?: return null
        val deploys = all.filter { valid(it) && it.path("event").asText() == "workflow_dispatch" &&
            it.path("path").asText() == ".github/workflows/deploy.yml" &&
            it.path("name").asText() == "Deploy $target" }
            .sortedByDescending { it.path("id").asLong() }
        for (deploy in deploys) {
            val runId = deploy.path("id").asLong()
            if (runId <= 0) continue
            val jobs = get(prefix, "actions/runs/$runId/jobs?per_page=$PAGE_SIZE", token) ?: continue
            if (jobs.path("total_count").asInt(PAGE_SIZE) >= PAGE_SIZE) continue
            val allJobs = jobs.path("jobs").takeIf { it.isArray }?.toList() ?: continue
            for (job in allJobs.filter { it.path("name").asText() == "deploy to workstream" &&
                it.path("conclusion").asText() == "success" }.sortedByDescending { it.path("id").asLong() }) {
                val jobId = job.path("id").asLong()
                if (jobId <= 0) continue
                val log = api.getJobLog("$prefix/actions/jobs/$jobId/logs", token)?.decodeToString() ?: continue
                val receipt = parseLog(log) ?: continue
                if (receipt.second != target) continue
                val ref = get(prefix, "git/ref/tags/${receipt.first}", token) ?: continue
                if (ref.path("object").path("type").asText() != "commit" ||
                    ref.path("object").path("sha").asText() != integrationCommit) continue
                return Evidence(repository.fullName, workCommit, integrationBranch, integrationCommit,
                    build.path("id").asLong(), runId, jobId, receipt.first, target)
            }
        }
        return null
    }

    private fun get(prefix: String, path: String, token: String): JsonNode? {
        val response = api.get("$prefix/$path", token)
        if (response.status == 404) return null
        check(response.status == 200) { "GitHub evidence unavailable (HTTP ${response.status})" }
        return response.body
    }

    private fun parseLog(log: String): Pair<String, String>? {
        val tags = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        for (line in log.lineSequence()) {
            val clean = ANSI.replace(line, "")
            TAG.find(clean)?.groupValues?.get(1)?.let(tags::add)
            TARGET_LINE.find(clean)?.groupValues?.get(1)?.let(targets::add)
        }
        return if (tags.size == 1 && targets.size == 1) tags.single() to targets.single() else null
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_CANDIDATES = 20
        val SHA = Regex("^[0-9a-f]{40}$")
        // Mirrors Whoz tools/deploy/src/lib/workstream-target.utils.ts: exactly
        // integration/<workstream> maps to forge-<workstream>.
        val BRANCH = Regex("^integration/([a-z0-9]+(?:-[a-z0-9]+)*)$")
        val ANSI = Regex("\\u001B\\[[0-9;]*[A-Za-z]")
        val TAG = Regex("(?:^|\\s)RELEASE_TAG:\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})(?:\\s|$)")
        val TARGET_LINE = Regex("(?:^|\\s)TARGET_ENVIRONMENT:\\s*([A-Za-z0-9][A-Za-z0-9._-]{0,127})(?:\\s|$)")
    }
}
