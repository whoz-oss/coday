package io.whozoss.factory.verification.snapshot

import io.whozoss.factory.verification.oracle.OracleExecutor
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Working-tree snapshot and diff — ported from `snapshotDiff` / `diffSince` in
 * `factory/src/application/oracle/oracle-executor.ts` and `diffSnapshots` in
 * `factory/src/domain/oracle/oracle.ts`.
 *
 * Git is only used to establish the LIST of files to watch; change detection comes
 * from the content. For each tracked-modified and untracked file, the content is
 * fingerprinted with SHA-256 so a one-line-for-one-line rewrite is still detected.
 */
object WorkspaceSnapshot {

    /** Sentinel used when a file disappeared or is unreadable between listing and reading. */
    const val UNREADABLE = "unreadable"

    /** Snapshot of the working tree: content fingerprints by path. */
    data class Snapshot(
        val modified: Map<String, String>,
        val untracked: Map<String, String>,
    )

    /** Paths whose content changed between two snapshots. */
    data class Delta(
        val modified: List<String>,
        val untracked: List<String>,
    )

    /**
     * Takes a snapshot of the current Git state under [cwd]. Git establishes the
     * file list (`git diff HEAD --name-only` + `git ls-files --others
     * --exclude-standard`); the SHA-256 of each file's content is the measure.
     */
    fun snapshot(cwd: Path): Snapshot {
        val diffResult = OracleExecutor.runCommand("git diff HEAD --name-only", cwd = cwd)
        val untrackedResult = OracleExecutor.runCommand("git ls-files --others --exclude-standard", cwd = cwd)

        val modified = linkedMapOf<String, String>()
        for (path in splitLines(diffResult.stdout)) {
            modified[path] = contentFingerprint(cwd, path)
        }

        val untracked = linkedMapOf<String, String>()
        for (path in splitLines(untrackedResult.stdout)) {
            untracked[path] = contentFingerprint(cwd, path)
        }

        return Snapshot(modified = modified, untracked = untracked)
    }

    /**
     * Pure comparison of two snapshots. A path is retained if it appeared or if its
     * fingerprint changed. The result is a list of paths — fingerprints are a
     * detection means, not a recorded fact.
     */
    fun diff(before: Snapshot, after: Snapshot): Delta {
        val modified = after.modified.entries
            .filter { before.modified[it.key] != it.value }
            .map { it.key }
        val untracked = after.untracked.entries
            .filter { before.untracked[it.key] != it.value }
            .map { it.key }
        return Delta(modified = modified, untracked = untracked)
    }

    /** Returns paths whose content changed since a previous snapshot. */
    fun diffSince(before: Snapshot, cwd: Path): Delta = diff(before, snapshot(cwd))

    /** True when the delta contains no modified and no untracked change. */
    fun wroteNothing(delta: Delta): Boolean = delta.modified.isEmpty() && delta.untracked.isEmpty()

    /**
     * Fingerprint of a file: SHA-256 digest of its content. Used for tracked and
     * untracked files alike — a single measure, no assumption about the shape of
     * the change.
     */
    fun contentFingerprint(cwd: Path, relPath: String): String {
        return try {
            val bytes = Files.readAllBytes(cwd.resolve(relPath))
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // File gone or unreadable between listing and reading.
            // A sentinel distinct from any digest: a disappearance is a change.
            UNREADABLE
        }
    }

    private fun splitLines(value: String): List<String> = value.split("\n").filter { it.isNotBlank() }
}
