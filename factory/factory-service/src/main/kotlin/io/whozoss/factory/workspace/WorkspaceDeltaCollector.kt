package io.whozoss.factory.workspace

import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.sdk.spi.WatchedFileSnapshot
import io.whozoss.factory.sdk.spi.WorkspaceCaptureStatus
import io.whozoss.factory.sdk.spi.WorkspaceDelta
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Raw point-in-time snapshot of an AgentOS case workspace captured before or
 * after an agent turn (Lot 2, §6.1).
 */
data class WorkspaceTurnSnapshot(
    val captureStatus: WorkspaceCaptureStatus,
    val degradedReason: String? = null,
    val worktreePath: String? = null,
    val branch: String? = null,
    val baseSha: String? = null,
    val changedFileStatuses: Map<String, String> = emptyMap(),
    val changedFilePatchHashes: Map<String, String?> = emptyMap(),
    val watchedFiles: Map<String, WatchedFilePoint> = emptyMap(),
)

/**
 * Point-in-time state of a watched file at the start or end of an agent turn.
 */
data class WatchedFilePoint(
    val path: String,
    val exists: Boolean,
    val contentSha256: String? = null,
    val patch: String? = null,
    val patchMessage: String? = null,
)

/**
 * Captures before/after workspace snapshots around an agent turn and computes
 * the attributable [WorkspaceDelta] (Lot 2, §6.1).
 *
 * Source of truth:
 * - AgentOS `GET /api/cases/{caseId}/workspace/changes` provides `equipped`,
 *   `status`, `path` (worktree path), `branch`, `changes.base` and `changes.files[]`.
 * - AgentOS `GET /api/cases/{caseId}/workspace/diff?path=...` provides the
 *   cumulative patch against `base` (and detects truncated/binary diffs).
 * - For `watchedPaths`, the exact current `contentSha256` and `exists` boolean
 *   are computed safely inside the resolved worktree path (preventing path
 *   traversal or symlink escape), or from an explicit AgentOS field when
 *   supplied by a test/mock.
 */
@Service
class WorkspaceDeltaCollector(
    private val agentOsProxyClient: AgentOsProxyClient? = null,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Captures a point-in-time snapshot of [caseId]'s workspace for the given
     * [watchedPaths]. Never throws: an unreachable or unequipped workspace is
     * reported via [WorkspaceCaptureStatus.UNAVAILABLE] or [WorkspaceCaptureStatus.DEGRADED].
     */
    fun captureSnapshot(
        caseId: String,
        watchedPaths: Collection<String> = emptyList(),
        fallbackWorktreeRoot: Path? = null,
        externalUserId: String? = null,
    ): WorkspaceTurnSnapshot {
        val proxy = agentOsProxyClient
            ?: return WorkspaceTurnSnapshot(
                captureStatus = WorkspaceCaptureStatus.UNAVAILABLE,
                degradedReason = "AGENTOS_PROXY_UNAVAILABLE",
            )

        val rawEnv = try {
            proxy.fetchWorkspaceChanges(caseId, externalUserId)
        } catch (error: Exception) {
            logger.warn(error) { "Could not fetch workspace changes for case '$caseId'" }
            return WorkspaceTurnSnapshot(
                captureStatus = WorkspaceCaptureStatus.UNAVAILABLE,
                degradedReason = "WORKSPACE_CHANGES_ERROR: ${error.message ?: error.javaClass.simpleName}",
            )
        } ?: return WorkspaceTurnSnapshot(
            captureStatus = WorkspaceCaptureStatus.UNAVAILABLE,
            degradedReason = "WORKSPACE_NOT_FOUND",
        )

        val equipped = rawEnv["equipped"] as? Boolean ?: false
        val status = rawEnv["status"] as? String
        if (!equipped || (status != null && status != "READY")) {
            return WorkspaceTurnSnapshot(
                captureStatus = WorkspaceCaptureStatus.UNAVAILABLE,
                degradedReason = "WORKSPACE_NOT_READY (equipped=$equipped, status=$status)",
            )
        }

        val envError = rawEnv["error"] as? String
        if (!envError.isNullOrBlank()) {
            return WorkspaceTurnSnapshot(
                captureStatus = WorkspaceCaptureStatus.DEGRADED,
                degradedReason = envError,
            )
        }

        val worktreePath = (rawEnv["path"] as? String)?.takeIf { it.isNotBlank() }
            ?: fallbackWorktreeRoot?.toAbsolutePath()?.normalize()?.toString()
        val branch = rawEnv["branch"] as? String
        val changesMap = rawEnv["changes"] as? Map<*, *>
        val baseSha = changesMap?.get("base") as? String
        val rawFiles = (changesMap?.get("files") as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

        val changedStatuses = LinkedHashMap<String, String>()
        val changedPatchHashes = LinkedHashMap<String, String?>()
        val rawFileMap = LinkedHashMap<String, Map<*, *>>()
        var degradedReason: String? = null

        for (fileEntry in rawFiles) {
            val path = fileEntry["path"] as? String ?: continue
            val fileStatus = (fileEntry["status"] as? String) ?: "MODIFIED"
            changedStatuses[path] = fileStatus
            rawFileMap[path] = fileEntry

            if (fileStatus != "DELETED") {
                val diffMap = runCatching { proxy.fetchWorkspaceDiff(caseId, path, externalUserId) }.getOrNull()
                val patch = diffMap?.get("patch") as? String
                val msg = diffMap?.get("message") as? String
                if (msg != null && msg.contains("too large to preview", ignoreCase = true)) {
                    degradedReason = "PATCH_TOO_LARGE:$path"
                }
                changedPatchHashes[path] = patch?.let(::sha256Hex)
            } else {
                changedPatchHashes[path] = "DELETED"
            }
        }

        val resolvedRootPath = worktreePath?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        val watchedSnapshots = LinkedHashMap<String, WatchedFilePoint>()

        for (watchedPath in watchedPaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val fileEntry = rawFileMap[watchedPath]
            val diffMap = if (watchedPath in changedStatuses && changedStatuses[watchedPath] != "DELETED") {
                runCatching { proxy.fetchWorkspaceDiff(caseId, watchedPath, externalUserId) }.getOrNull()
            } else {
                null
            }
            val patch = diffMap?.get("patch") as? String
            val patchMessage = diffMap?.get("message") as? String
            if (patchMessage != null && patchMessage.contains("too large to preview", ignoreCase = true)) {
                degradedReason = "WATCHED_PATCH_TOO_LARGE:$watchedPath"
            }

            // 1. Prefer explicit sha256/exists if supplied by AgentOS payload (e.g. enriched API or test double)
            val explicitSha = (fileEntry?.get("contentSha256") as? String)
                ?: (diffMap?.get("contentSha256") as? String)
            val explicitExists = (fileEntry?.get("exists") as? Boolean)
                ?: (diffMap?.get("exists") as? Boolean)

            val (exists, contentSha) = when {
                explicitSha != null -> (explicitExists ?: true) to explicitSha
                explicitExists == false || changedStatuses[watchedPath] == "DELETED" -> false to null
                resolvedRootPath != null -> readSafeFileSha256(resolvedRootPath, watchedPath)
                // If no local worktree directory is mounted in a unit test, derive content hash from
                // an untracked full-file patch (`--no-index` against `/dev/null`) or the patch itself.
                patch != null -> true to sha256Hex(patch)
                else -> false to null
            }

            watchedSnapshots[watchedPath] = WatchedFilePoint(
                path = watchedPath,
                exists = exists,
                contentSha256 = contentSha,
                patch = patch,
                patchMessage = patchMessage,
            )
        }

        val finalStatus = if (degradedReason != null) {
            WorkspaceCaptureStatus.DEGRADED
        } else {
            WorkspaceCaptureStatus.COMPLETE
        }

        return WorkspaceTurnSnapshot(
            captureStatus = finalStatus,
            degradedReason = degradedReason,
            worktreePath = worktreePath,
            branch = branch,
            baseSha = baseSha,
            changedFileStatuses = changedStatuses,
            changedFilePatchHashes = changedPatchHashes,
            watchedFiles = watchedSnapshots,
        )
    }

    /**
     * Computes the [WorkspaceDelta] attributable to the single turn between
     * [before] and [after] (§6.1).
     */
    fun computeDelta(
        before: WorkspaceTurnSnapshot,
        after: WorkspaceTurnSnapshot,
        watchedPaths: Collection<String> = emptyList(),
    ): WorkspaceDelta {
        if (before.captureStatus == WorkspaceCaptureStatus.UNAVAILABLE ||
            after.captureStatus == WorkspaceCaptureStatus.UNAVAILABLE
        ) {
            return WorkspaceDelta(
                captureStatus = WorkspaceCaptureStatus.UNAVAILABLE,
                headShaBefore = before.baseSha,
                headShaAfter = after.baseSha,
                degradedReason = after.degradedReason ?: before.degradedReason ?: "WORKSPACE_UNAVAILABLE",
            )
        }

        val captureStatus = if (before.captureStatus == WorkspaceCaptureStatus.DEGRADED ||
            after.captureStatus == WorkspaceCaptureStatus.DEGRADED
        ) {
            WorkspaceCaptureStatus.DEGRADED
        } else {
            WorkspaceCaptureStatus.COMPLETE
        }

        // Files touched during this specific turn:
        // - appeared in `after` with a different patch hash or status than in `before`
        // - or disappeared (were changed in `before` and reverted/deleted in `after`)
        val changedDuringTurn = LinkedHashMap<String, String>()
        for ((path, statusAfter) in after.changedFileStatuses) {
            val statusBefore = before.changedFileStatuses[path]
            val hashBefore = before.changedFilePatchHashes[path]
            val hashAfter = after.changedFilePatchHashes[path]
            if (statusBefore == null || statusBefore != statusAfter || hashBefore != hashAfter) {
                changedDuringTurn[path] = statusAfter
            }
        }

        val allWatched = (watchedPaths + before.watchedFiles.keys + after.watchedFiles.keys).distinct()
        val watchedMap = LinkedHashMap<String, WatchedFileSnapshot>()
        for (path in allWatched) {
            val ptBefore = before.watchedFiles[path]
            val ptAfter = after.watchedFiles[path]
            val existsBefore = ptBefore?.exists ?: false
            val shaBefore = ptBefore?.contentSha256
            val existsAfter = ptAfter?.exists ?: false
            val shaAfter = ptAfter?.contentSha256
            watchedMap[path] = WatchedFileSnapshot(
                path = path,
                existsBefore = existsBefore,
                sha256Before = shaBefore,
                existsAfter = existsAfter,
                sha256After = shaAfter,
                patchAfter = ptAfter?.patch,
            )
            // Also ensure a watched file whose content SHA-256 changed during the turn
            // is listed in `changedFilesDuringTurn` even if it was already in the branch diff.
            if (shaBefore != shaAfter || existsBefore != existsAfter) {
                if (path !in changedDuringTurn) {
                    changedDuringTurn[path] = when {
                        !existsBefore && existsAfter -> "ADDED"
                        existsBefore && !existsAfter -> "DELETED"
                        else -> "MODIFIED"
                    }
                }
            }
        }

        return WorkspaceDelta(
            captureStatus = captureStatus,
            headShaBefore = before.baseSha,
            headShaAfter = after.baseSha,
            changedFilesDuringTurn = changedDuringTurn,
            watchedFiles = watchedMap,
            degradedReason = after.degradedReason ?: before.degradedReason,
        )
    }

    private fun readSafeFileSha256(root: Path, relative: String): Pair<Boolean, String?> {
        return runCatching {
            val rel = Path.of(relative)
            if (rel.isAbsolute || relative.isBlank() || rel.any { it.toString() in setOf("..", ".git") }) {
                return false to null
            }
            val resolved = root.resolve(rel).normalize()
            if (!resolved.startsWith(root)) return false to null
            if (!Files.exists(resolved) || !Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved)) {
                return false to null
            }
            val bytes = Files.readAllBytes(resolved)
            true to sha256Hex(bytes)
        }.getOrDefault(false to null)
    }

    companion object {
        fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
