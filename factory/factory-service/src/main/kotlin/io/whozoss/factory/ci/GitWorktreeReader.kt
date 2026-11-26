package io.whozoss.factory.ci

import java.nio.file.Files
import java.nio.file.Path

/**
 * Read-only view of a Git worktree: the attached branch, the HEAD commit and the `origin` remote, read from the
 * files of the Git directory. No `git` process is ever spawned and the worktree is never mutated. Supports a
 * normal repository (`.git` directory) and a linked worktree (`.git` file with a `gitdir:` line).
 */
internal object GitWorktreeReader {

    private val GIT_SHA = Regex("^[0-9a-fA-F]{40}$")
    private val GITHUB_REMOTE = Regex("github\\.com[:/]+([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?/?$")

    /** The attached branch name, or null when HEAD is detached or unreadable. */
    fun branch(repoRoot: Path): String? = runCatching {
        val head = Files.readString(gitDir(repoRoot).resolve("HEAD")).trim()
        head.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** The HEAD commit sha when it can be resolved from loose or packed refs, else null. */
    fun head(repoRoot: Path): String? = runCatching {
        val gitDir = gitDir(repoRoot)
        val head = Files.readString(gitDir.resolve("HEAD")).trim()
        if (!head.startsWith("ref:")) return@runCatching head.takeIf { GIT_SHA.matches(it) }
        val ref = head.removePrefix("ref:").trim()
        val loose = gitDir.resolve(ref)
        if (Files.isRegularFile(loose)) return@runCatching Files.readString(loose).trim().takeIf { GIT_SHA.matches(it) }
        val common = commonDir(gitDir)
        val commonLoose = common.resolve(ref)
        if (Files.isRegularFile(commonLoose)) return@runCatching Files.readString(commonLoose).trim().takeIf { GIT_SHA.matches(it) }
        Files.readAllLines(common.resolve("packed-refs")).asSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith(" $ref") }
            ?.substringBefore(' ')
            ?.takeIf { GIT_SHA.matches(it) }
    }.getOrNull()

    /** `owner` / `repo` of the GitHub `origin` remote (https or ssh form), or null when it is not GitHub. */
    fun githubOrigin(repoRoot: Path): Pair<String, String>? = runCatching {
        val config = Files.readAllLines(commonDir(gitDir(repoRoot)).resolve("config"))
        var inOrigin = false
        for (raw in config) {
            val line = raw.trim()
            if (line.startsWith("[")) inOrigin = line.replace(" ", "") == "[remote\"origin\"]"
            else if (inOrigin && line.startsWith("url")) {
                val url = line.substringAfter('=').trim()
                return@runCatching GITHUB_REMOTE.find(url)?.let { it.groupValues[1] to it.groupValues[2] }
            }
        }
        null
    }.getOrNull()

    private fun gitDir(repoRoot: Path): Path {
        val dotGit = repoRoot.toAbsolutePath().normalize().resolve(".git")
        if (Files.isDirectory(dotGit)) return dotGit
        val line = Files.readString(dotGit).lineSequence().firstOrNull()?.trim().orEmpty()
        val raw = line.removePrefix("gitdir:").trim()
        require(raw.isNotEmpty()) { "not a git worktree" }
        val path = Path.of(raw)
        return if (path.isAbsolute) path.normalize() else dotGit.parent.resolve(path).normalize()
    }

    /** The shared Git directory (holds `config` and `packed-refs`); the directory itself for a normal repository. */
    private fun commonDir(gitDir: Path): Path {
        val file = gitDir.resolve("commondir")
        if (!Files.isRegularFile(file)) return gitDir
        val path = Path.of(Files.readString(file).trim())
        return if (path.isAbsolute) path else gitDir.resolve(path).normalize()
    }
}
