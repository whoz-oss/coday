package io.whozoss.factory.environment.port

import io.whozoss.factory.environment.domain.WorkEnvironment
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.springframework.stereotype.Component

fun interface GitCommandRunner { fun run(root: Path, arguments: List<String>): GitCommandResult }
data class GitCommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Shared bounded Git runner for Factory-owned read and mutation operations. */
@Component
class BoundedGitCommandRunner : GitCommandRunner {
    override fun run(root: Path, arguments: List<String>): GitCommandResult {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(root.toFile())
            .redirectErrorStream(false)
            .start()
        return if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            GitCommandResult(124, "", "git command timed out")
        } else {
            GitCommandResult(
                process.exitValue(),
                process.inputStream.readAllBytes().toString(Charsets.ISO_8859_1),
                process.errorStream.readAllBytes().toString(Charsets.UTF_8),
            )
        }
    }

    private companion object {
        const val GIT_TIMEOUT_SECONDS = 30L
    }
}

data class WorktreeBaseline(
    val hash: String,
    val pathCount: Int,
    val dirty: Boolean,
    val paths: List<String>,
)

/** Factory-owned branch preparation which preserves inherited local changes byte-for-byte. */
@Component
class LocalWorktreeEnvironmentProvider(
    private val runner: GitCommandRunner = BoundedGitCommandRunner(),
) : EnvironmentGitProvider {
    override val providerId: String = "local-worktree"
    override fun supports(environment: WorkEnvironment): Boolean = environment.envType == WorkEnvironment.DEFAULT_ENV_TYPE

    override fun inspect(environment: WorkEnvironment, expectedEnvironmentRevision: Int): EnvironmentGitInspectionResult {
        val lock = locks.computeIfAbsent(environment.environmentId) { ReentrantLock() }
        lock.lock()
        try {
            if (environment.revision != expectedEnvironmentRevision) {
                return EnvironmentGitInspectionResult.Refused(
                    EnvironmentGitFacts(providerId, environment.environmentId, environment.revision, null, null, null, null,
                        mutationApplied = false, failureStage = "revision_mismatch", message = "Environment revision mismatch."),
                )
            }
            val root = Path.of(environment.worktreePath).toAbsolutePath().normalize()
            if (!Files.isDirectory(root) || !Files.exists(root.resolve(".git"))) {
                return EnvironmentGitInspectionResult.Refused(
                    EnvironmentGitFacts(providerId, environment.environmentId, environment.revision, null, null, null, null,
                        mutationApplied = false, failureStage = "inspection_failed", message = "Environment Git worktree is unavailable."),
                )
            }
            val realRoot = root.toRealPath()
            val head = git(realRoot, listOf("rev-parse", "HEAD"))
            val branch = git(realRoot, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
            val snapshot = baseline(realRoot)
            if (head == null || !SHA.matches(head) || snapshot == null) {
                return EnvironmentGitInspectionResult.Refused(
                    EnvironmentGitFacts(providerId, environment.environmentId, environment.revision, null, branch, head, snapshot?.hash,
                        snapshot?.pathCount, snapshot?.paths ?: emptyList(), snapshot?.dirty, false, "inspection_failed", "Cannot inspect environment Git state."),
                )
            }
            return EnvironmentGitInspectionResult.Inspected(
                EnvironmentGitInspection(providerId, environment.environmentId, environment.revision, head, branch,
                    snapshot.hash, snapshot.pathCount, snapshot.paths, snapshot.dirty,
                    inspectionToken(environment, head, branch, snapshot.hash)),
            )
        } finally { lock.unlock() }
    }

    override fun prepareBranch(
        environment: WorkEnvironment,
        command: PrepareEnvironmentBranchCommand,
    ): PrepareEnvironmentBranchResult {
        if (command.environmentRef != environment.environmentId || command.expectedEnvironmentRevision != environment.revision) {
            return PrepareEnvironmentBranchResult.Refused(facts(environment, command, null, false, "binding_mismatch", "Environment binding/revision mismatch."))
        }
        val lock = locks.computeIfAbsent(environment.environmentId) { ReentrantLock() }
        lock.lock()
        try {
            return prepareLocked(environment, command)
        } finally {
            lock.unlock()
        }
    }

    private fun prepareLocked(environment: WorkEnvironment, command: PrepareEnvironmentBranchCommand): PrepareEnvironmentBranchResult = try {
        val root = Path.of(environment.worktreePath).toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return refusal(environment, command, null, false, "not_started", "Run worktree does not exist.")
        val realRoot = root.toRealPath()
        if (!Files.exists(realRoot.resolve(".git"))) return refusal(environment, command, null, false, "not_started", "Environment is not a Git worktree.")
        val head = git(realRoot, listOf("rev-parse", "HEAD")) ?: return refusal(environment, command, null, false, "not_started", "Cannot read environment HEAD.")
        val current = git(realRoot, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        val before = baseline(realRoot) ?: return refusal(environment, command, current, false, "not_started", "Cannot calculate environment baseline.")
        val token = inspectionToken(environment, head, current, before.hash)
        if (!SHA.matches(head) || head != command.expectedHead || before.hash != command.expectedBaselineHash || token != command.inspectionToken) {
            return refusal(environment, command, current, false, "inspection_drift", "Environment changed after inspection; no mutation attempted.")
        }
        val branch = command.branchName
        var mutationApplied = false
        if (current != branch) {
            val branchResult = runner.run(realRoot, listOf("rev-parse", "--verify", "refs/heads/$branch"))
            val branchHead = branchResult.stdout.trim().takeIf { branchResult.exitCode == 0 }
            if (branchResult.exitCode !in setOf(0, 1, 128)) return refusal(environment, command, current, false, "refused", "Cannot inspect existing branch.")
            val switched = if (branchHead != null) {
                if (branchHead != head) return refusal(environment, command, current, false, "refused", "Existing branch does not point at expectedHead.")
                runner.run(realRoot, listOf("switch", branch))
            } else runner.run(realRoot, listOf("switch", "-c", branch))
            if (switched.exitCode != 0) return refusal(environment, command, current, false, "refused", "Git could not prepare branch; inspect environment manually.")
            mutationApplied = true
        }
        val observedHead = git(realRoot, listOf("rev-parse", "HEAD"))
        val observedBranch = git(realRoot, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        if (observedHead != head || observedBranch != branch) return refusal(environment, command, current, mutationApplied, "postcondition_failed", "Branch/HEAD postcondition mismatch; inspect environment manually.")
        val after = baseline(realRoot) ?: return refusal(environment, command, current, mutationApplied, "postcondition_failed", "Cannot calculate post-switch baseline; inspect environment manually.")
        if (after.hash != before.hash || after.pathCount != before.pathCount) {
            return refusal(environment, command, current, mutationApplied, "postcondition_failed", "Local state changed during branch preparation; inspect environment manually.")
        }
        PrepareEnvironmentBranchResult.Prepared(facts(environment, command, before, mutationApplied, "branch_created_or_switched", null, current, head))
    } catch (error: Exception) {
        refusal(environment, command, null, false, "not_started", "Branch preparation failed; inspect state manually: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun baseline(root: Path): WorktreeBaseline? = runCatching {
        val result = runner.run(root, listOf("status", "--porcelain=v1", "-z", "--untracked-files=all"))
        if (result.exitCode != 0) return@runCatching null
        val records = result.stdout.split('\u0000').filter { it.isNotEmpty() }
        val paths = parsePaths(records)
        val digest = MessageDigest.getInstance("SHA-256")
        records.forEach { record ->
            val bytes = record.toByteArray(Charsets.ISO_8859_1)
            digest.update(intBytes(bytes.size)); digest.update(bytes)
        }
        paths.forEach { relative ->
            val path = root.resolve(relative).normalize()
            if (!path.startsWith(root) || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) error("Baseline path is unavailable")
            digest.update(intBytes(relative.toByteArray().size)); digest.update(relative.toByteArray())
            if (Files.isSymbolicLink(path)) digest.update(Files.readSymbolicLink(path).toString().toByteArray())
            else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            }
        }
        WorktreeBaseline("sha256:${hex(digest.digest())}", paths.size, records.isNotEmpty(), paths.take(MAX_PATHS).map { it.take(MAX_PATH_LENGTH) })
    }.getOrNull()

    private fun parsePaths(records: List<String>): List<String> {
        val paths = mutableListOf<String>(); var index = 0
        while (index < records.size) {
            val record = records[index++]
            if (record.length < 4) error("Malformed Git status record")
            val status = record.substring(0, 2); paths += record.substring(3)
            if ('R' in status || 'C' in status) paths += records.getOrNull(index++) ?: error("Malformed rename record")
        }
        return paths.distinct().sorted()
    }

    private fun inspectionToken(environment: WorkEnvironment, head: String, branch: String?, baselineHash: String): String =
        "sha256:${hex(MessageDigest.getInstance("SHA-256").digest(
            "${environment.environmentId}\u0000${environment.revision}\u0000$head\u0000${branch ?: ""}\u0000$baselineHash".toByteArray(),
        ))}"

    private fun facts(
        environment: WorkEnvironment, command: PrepareEnvironmentBranchCommand, baseline: WorktreeBaseline?, mutationApplied: Boolean,
        stage: String, message: String?, previousBranch: String? = null, head: String? = command.expectedHead,
    ) = EnvironmentGitFacts(providerId, environment.environmentId, environment.revision, command.branchName, previousBranch, head,
        baseline?.hash, baseline?.pathCount, baseline?.paths ?: emptyList(), baseline?.dirty, mutationApplied, stage, message?.take(4096))
    private fun refusal(environment: WorkEnvironment, command: PrepareEnvironmentBranchCommand, previousBranch: String?, mutationApplied: Boolean, stage: String, message: String) =
        PrepareEnvironmentBranchResult.Refused(facts(environment, command, null, mutationApplied, stage, message, previousBranch))
    private fun git(root: Path, arguments: List<String>): String? = runner.run(root, arguments).let { it.stdout.trim().takeIf { _ -> it.exitCode == 0 } }
    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
    private companion object {
        val SHA = Regex("^[0-9a-fA-F]{40}$")
        val locks = ConcurrentHashMap<String, ReentrantLock>()
        const val MAX_PATHS = 100
        const val MAX_PATH_LENGTH = 512
    }
}
