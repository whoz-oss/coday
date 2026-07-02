package io.whozoss.agentos.exchange

import mu.KLogging
import org.springframework.stereotype.Service
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.stream.Collectors

/**
 * Filesystem-backed storage for the AgentOS file exchange.
 *
 * The exchange is split into two physically separate roots per namespace so that
 * the namespace-shared manifest never sees case-private folders:
 * - [caseRoot]      = `<mountRoot>/<namespaceId>/cases/<YYYY>/<MM>/<DD>/<caseId>` (sharded by creation date)
 * - [namespaceRoot] = `<mountRoot>/<namespaceId>/shared`
 *
 * Relative-path resolution only guards against traversal (the resolved path must stay within the
 * scope root). Unlike the LLM-facing file tools, the user-driven exchange applies no sensitive-file
 * deny-list — users manage their own files.
 */
@Service
class ExchangeStorageService(
    private val config: ExchangeStorageConfigProperties,
) {
    companion object : KLogging() {
        private val PID = ProcessHandle.current().pid()
        private const val MAX_SEGMENT_LENGTH = 255
    }

    private val mountRoot: Path = Path.of(config.mountRoot)

    /**
     * Whether an upload with this leaf/relative path passes the configured extension allow-list.
     * An empty [ExchangeStorageConfigProperties.allowedUploadExtensions] allows any extension.
     */
    fun isUploadAllowed(relativePath: String): Boolean {
        val allowed = config.allowedUploadExtensions
        val extension = relativePath.substringAfterLast('.', "").lowercase()
        return allowed.isEmpty() || (extension.isNotEmpty() && extension in allowed)
    }

    /**
     * Root holding files private to [caseId] within [namespaceId].
     *
     * Sharded by the case's creation date (UTC) — `cases/<YYYY>/<MM>/<DD>/<caseId>` — to keep the
     * per-directory child count well under filesystem limits as cases accumulate. [createdAt] must
     * be the case's immutable creation timestamp so the resolved path is stable across resolutions.
     */
    fun caseRoot(
        namespaceId: UUID,
        caseId: UUID,
        createdAt: Instant,
    ): Path {
        val day = createdAt.atOffset(ZoneOffset.UTC)
        return mountRoot
            .resolve(namespaceId.toString())
            .resolve("cases")
            .resolve("%04d".format(day.year))
            .resolve("%02d".format(day.monthValue))
            .resolve("%02d".format(day.dayOfMonth))
            .resolve(caseId.toString())
    }

    /** Root holding files shared across [namespaceId]. */
    fun namespaceRoot(namespaceId: UUID): Path = mountRoot.resolve(namespaceId.toString()).resolve("shared")

    /**
     * List every regular file under [root].
     *
     * Returns an empty list if [root] does not exist (no scope has been written yet).
     * Paths are relative to [root] with forward slashes.
     */
    fun listManifest(
        root: Path,
        scope: ExchangeScope,
    ): List<ExchangeFileEntry> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { toEntry(root, it, scope) }
                .collect(Collectors.toList())
        }
    }

    /**
     * Read the UTF-8 text content of [relativePath] under [root].
     *
     * @throws IllegalArgumentException if the path escapes the scope boundary.
     * @throws java.nio.file.NoSuchFileException if the file does not exist.
     */
    fun readContent(
        root: Path,
        relativePath: String,
    ): ExchangeFileContent {
        val resolved = resolveWithin(root, relativePath)
        val content = Files.readString(resolved, StandardCharsets.UTF_8)
        val size = Files.size(resolved)
        val lastModified = Files.getLastModifiedTime(resolved).toInstant()
        return ExchangeFileContent(
            content = content,
            etag = computeEtag(size, lastModified),
            mimeType = mimeTypeFor(resolved.fileName.toString()),
            size = size,
        )
    }

    /**
     * Read the raw bytes of [relativePath] under [root] for download, with its MIME type.
     *
     * @throws IllegalArgumentException if the path escapes the scope boundary.
     * @throws java.nio.file.NoSuchFileException if the file does not exist.
     */
    fun readBytes(
        root: Path,
        relativePath: String,
    ): Pair<ByteArray, String?> {
        val resolved = resolveWithin(root, relativePath)
        return Files.readAllBytes(resolved) to mimeTypeFor(resolved.fileName.toString())
    }

    /**
     * Atomically create a new file at [relativePath] under [root].
     *
     * @throws FileExistsException if the target already exists (callers map to 409).
     * @throws IllegalArgumentException if the path escapes the boundary.
     * @return metadata for the newly created file.
     */
    fun writeNew(
        root: Path,
        relativePath: String,
        bytes: ByteArray,
        scope: ExchangeScope,
    ): ExchangeFileEntry {
        Files.createDirectories(root)
        val resolved = resolveWithin(root, relativePath)
        if (Files.exists(resolved)) {
            throw FileExistsException("File already exists: $relativePath")
        }
        resolved.parent?.let { Files.createDirectories(it) }

        // Stage to a sibling temp file then move into place. A plain move (no REPLACE_EXISTING)
        // fails with FileAlreadyExistsException if a concurrent writer won the race, keeping
        // writeNew create-only — without ATOMIC_MOVE, which silently overwrites on POSIX and
        // throws AtomicMoveNotSupportedException on filesystems that don't support it.
        val tmpPath = resolved.resolveSibling("${resolved.fileName}.$PID.${UUID.randomUUID()}.tmp")
        try {
            Files.write(tmpPath, bytes)
            Files.move(tmpPath, resolved)
        } finally {
            try {
                Files.deleteIfExists(tmpPath)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to clean up temp file: $tmpPath" }
            }
        }

        // `resolved` is canonical (built from the resolver's real root), so relativize
        // against the canonical root to obtain the stored relative path.
        return toEntry(root.toRealPath(), resolved, scope)
    }

    /**
     * Delete [relativePath] under [root].
     *
     * @throws IllegalArgumentException if the path escapes the scope boundary.
     * @throws java.nio.file.NoSuchFileException if the file does not exist.
     */
    fun delete(
        root: Path,
        relativePath: String,
    ) {
        val resolved = resolveWithin(root, relativePath)
        Files.delete(resolved)
    }

    /**
     * Resolve [relativePath] under [root], guarding only against path traversal (the resolved
     * path must stay within [root]). No sensitive-file deny-list is applied — this is the
     * user-driven exchange, not LLM file access.
     */
    private fun resolveWithin(
        root: Path,
        relativePath: String,
    ): Path {
        require(relativePath.split('/', '\\').all { it.length <= MAX_SEGMENT_LENGTH }) {
            "Invalid path: path segment too long ($relativePath)"
        }
        // Does not create [root]: reads/deletes of a never-written scope surface as
        // NoSuchFileException (→ 404) instead of materialising empty shard directories. Writers
        // create the root before calling this (see writeNew).
        val canonicalRoot = root.toRealPath()
        val resolved = canonicalRoot.resolve(relativePath).normalize()
        require(resolved.startsWith(canonicalRoot)) {
            "Invalid path: path traversal not allowed ($relativePath)"
        }
        // normalize() is lexical and does not follow symlinks, so a symlink *inside* the root could
        // still point outside it. Canonicalize the deepest existing ancestor (the file itself for a
        // read/delete, the parent dir for a create) and require it to stay within the canonical root.
        val deepestExisting = generateSequence(resolved) { it.parent }.first(Files::exists)
        require(deepestExisting.toRealPath().startsWith(canonicalRoot)) {
            "Invalid path: path traversal not allowed ($relativePath)"
        }
        return resolved
    }

    /** Map a regular file to an [ExchangeFileEntry], with its path relative to [baseRoot]. */
    private fun toEntry(
        baseRoot: Path,
        file: Path,
        scope: ExchangeScope,
    ): ExchangeFileEntry {
        val size = Files.size(file)
        val lastModified = Files.getLastModifiedTime(file).toInstant()
        val relativePath = baseRoot.relativize(file).joinToString("/") { it.toString() }
        return ExchangeFileEntry(
            path = relativePath,
            filename = file.fileName.toString(),
            size = size,
            lastModified = lastModified,
            mimeType = mimeTypeFor(file.fileName.toString()),
            scope = scope,
            etag = computeEtag(size, lastModified),
        )
    }

    /** A display/Content-Type hint derived from the filename extension (no per-file I/O). */
    private fun mimeTypeFor(filename: String): String? =
        URLConnection.guessContentTypeFromName(filename) ?: textMimeFallback(filename)

    /** Fallback for common text formats `guessContentTypeFromName` doesn't recognise (md/csv/yaml/log…). */
    private fun textMimeFallback(filename: String): String? =
        when (filename.substringAfterLast('.', "").lowercase()) {
            "md", "markdown", "csv", "tsv", "yaml", "yml", "log", "txt" -> "text/plain"
            "json" -> "application/json"
            else -> null
        }

    /** Short, stable entity tag derived from a file's size and modification time. */
    private fun computeEtag(
        size: Long,
        lastModified: Instant,
    ): String = Integer.toHexString("$size:${lastModified.toEpochMilli()}".hashCode())
}
