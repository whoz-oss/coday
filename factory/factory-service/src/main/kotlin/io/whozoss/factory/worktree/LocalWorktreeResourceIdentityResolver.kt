package io.whozoss.factory.worktree

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a worktree identity within one local, co-located filesystem.
 *
 * `toRealPath` makes ordinary path aliases and symbolic links converge. This
 * does not identify equivalent bind mounts or remote/multi-host filesystems and
 * does not prevent the path being replaced after resolution (TOCTOU).
 */
class LocalWorktreeResourceIdentityResolver {
    fun resolve(path: Path): String {
        require(Files.exists(path)) { "Worktree path does not exist: $path" }
        require(Files.isDirectory(path)) { "Worktree path is not a directory: $path" }
        val canonical = try {
            path.toRealPath()
        } catch (error: Exception) {
            throw IllegalArgumentException("Worktree path cannot be canonicalized: $path", error)
        }
        require(Files.isDirectory(canonical)) { "Canonical worktree path is not a directory: $canonical" }
        return "$PREFIX$canonical"
    }

    companion object {
        const val PREFIX = "local-worktree:"
    }
}
