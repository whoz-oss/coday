package io.whozoss.agentos.plugins.factorybridge.persistence

import mu.KLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Atomic, owner-only file writes for the Factory Bridge durable state.
 *
 * Two concerns the plain `writeString` + `move` sequence did not address:
 *
 * 1. **Permissions.** A default-umask file is typically world-readable. The state holds
 *    capability tokens, so both the directory (`700`) and the file (`600`) are restricted
 *    to the owner. The temp file is created restricted *before* anything is written to it
 *    — widening later would leave a window where the content is readable.
 * 2. **Permission loss on rename.** `ATOMIC_MOVE` replaces the destination inode, so the
 *    permissions that survive are the temp file's. Restricting only the destination would
 *    be undone by the very next write.
 *
 * On a non-POSIX filesystem the permission calls are skipped (logged once per process):
 * the write still succeeds, the restriction simply does not apply. Failing the write would
 * trade a confidentiality weakness for an availability loss — the wrong trade for a store
 * whose purpose is to survive restarts.
 */
internal object RestrictedFiles : KLogging() {
    private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
    private val FILE_PERMISSIONS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    @Volatile
    private var warnedNonPosix = false

    /**
     * Writes [payload] to [target] atomically, owner-only.
     *
     * Creates the parent directory restricted if absent, writes to a sibling temp file
     * created with `600`, then renames over [target]. Falls back to a non-atomic move when
     * the filesystem refuses `ATOMIC_MOVE`.
     */
    fun writeAtomically(
        target: Path,
        payload: String,
    ) {
        target.parent?.let { createRestrictedDirectory(it) }
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.deleteIfExists(temp)
        createRestrictedFile(temp)
        Files.writeString(temp, payload)
        runCatching {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        // The renamed inode already carries the temp file's 600; this is a belt-and-braces
        // pass for the non-atomic fallback, where the destination inode may be reused.
        restrictExisting(target)
    }

    private fun createRestrictedDirectory(directory: Path) {
        if (Files.exists(directory)) {
            restrictExistingDirectory(directory)
            return
        }
        runCatching {
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        }.getOrElse {
            // Non-POSIX filesystem, or a parent created concurrently.
            Files.createDirectories(directory)
            restrictExistingDirectory(directory)
        }
    }

    private fun createRestrictedFile(file: Path) {
        runCatching {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS))
        }.getOrElse {
            Files.createFile(file)
            restrictExisting(file)
        }
    }

    private fun restrictExisting(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, FILE_PERMISSIONS) }
            .onFailure { warnNonPosixOnce(file) }
    }

    private fun restrictExistingDirectory(directory: Path) {
        runCatching { Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS) }
            .onFailure { warnNonPosixOnce(directory) }
    }

    private fun warnNonPosixOnce(path: Path) {
        if (warnedNonPosix) return
        warnedNonPosix = true
        logger.warn {
            "Factory bridge state at $path could not be restricted to owner-only permissions " +
                "(non-POSIX filesystem?) — rely on directory-level access control instead"
        }
    }
}
