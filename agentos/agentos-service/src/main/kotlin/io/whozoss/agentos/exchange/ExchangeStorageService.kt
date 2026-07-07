package io.whozoss.agentos.exchange

import mu.KLogging
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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
        private const val MAX_SEGMENT_LENGTH = 255

        // Cap manifest traversal depth (matches the file-plugin's SearchFilesTool) so a deeply
        // nested exchange tree can't turn a manifest request into an unbounded walk.
        private const val MANIFEST_MAX_DEPTH = 20
    }

    private val mountRoot: Path = Path.of(config.mountRoot)

    /**
     * Whether an upload with this relative path passes the configured extension allow-list.
     * An empty [ExchangeStorageConfigProperties.allowedUploadExtensions] allows any extension.
     *
     * This gate covers human uploads only (the two `POST /files` endpoints). Files produced by an
     * agent during a run go through the file-plugin tools, not this path, and are intentionally not
     * subject to the allow-list: they are trusted run output, not user-supplied uploads.
     */
    fun isUploadAllowed(relativePath: String): Boolean {
        // Normalise the configured allow-list (trim + lowercase) so an operator override like
        // "PDF, DOCX" still matches; the comparison extension below is already lowercased.
        val allowed = config.allowedUploadExtensions.mapTo(mutableSetOf()) { it.trim().lowercase() }
        // Derive the extension from the leaf filename only: a dot in a parent segment
        // (e.g. "v1.2/report") must not be mistaken for the file extension.
        val leaf = relativePath.substringAfterLast('/').substringAfterLast('\\')
        val extension = leaf.substringAfterLast('.', "").lowercase()
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
        val entries = mutableListOf<ExchangeFileEntry>()
        var rootFailure: IOException? = null
        // walkFileTree (not Files.walk) so an unreadable file OR subdirectory encountered mid-walk is
        // skipped via visitFileFailed rather than aborting the whole listing with an UncheckedIOException
        // (a 500 that would also leak the absolute server path). Symlinks are not followed.
        Files.walkFileTree(
            root,
            emptySet(),
            MANIFEST_MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isRegularFile) {
                        // A concurrent delete can make the file vanish before toEntry stats it: skip it.
                        // Any other stat failure is unexpected — skip it too, but log it (don't swallow silently).
                        runCatching { entries += toEntry(root, file, scope) }
                            .onFailure { e ->
                                if (e !is NoSuchFileException) {
                                    logger.warn(e) { "Skipping exchange file in manifest: $file" }
                                }
                            }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    // A failure to open the scope root itself (it exists but is unreadable: bad perms,
                    // broken mount, stale handle) must NOT be reported as an empty manifest — surface it
                    // below. Failures deeper in the tree are skipped so one bad file/dir can't hide the rest.
                    if (file == root) {
                        rootFailure = exc
                        logger.error(exc) { "Exchange scope root is unreadable: $root" }
                    } else {
                        logger.warn(exc) { "Skipping unreadable exchange path in manifest: $file" }
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        // Generic message on purpose (no absolute-path leak); the log above carries the details.
        if (rootFailure != null) throw IOException("Failed to list exchange files")
        return entries
    }

    /**
     * Read the UTF-8 text content of [relativePath] under [root].
     *
     * @throws InvalidExchangePathException if the path escapes the scope boundary.
     * @throws java.nio.file.NoSuchFileException if the file does not exist.
     */
    fun readContent(
        root: Path,
        relativePath: String,
    ): ExchangeFileContent {
        val resolved = resolveWithin(root, relativePath)
        val bytes = readWithinLimit(resolved)
        val lastModified = Files.getLastModifiedTime(resolved).toInstant()
        val size = bytes.size.toLong()
        return ExchangeFileContent(
            content = decodeUtf8Strict(bytes),
            etag = computeEtag(size, lastModified),
            mimeType = mimeTypeFor(resolved.fileName.toString()),
            size = size,
        )
    }

    /**
     * Read the raw bytes of [relativePath] under [root] for download, with its MIME type.
     *
     * @throws InvalidExchangePathException if the path escapes the scope boundary.
     * @throws java.nio.file.NoSuchFileException if the file does not exist.
     */
    fun readBytes(
        root: Path,
        relativePath: String,
    ): Pair<ByteArray, String?> {
        val resolved = resolveWithin(root, relativePath)
        return readWithinLimit(resolved) to mimeTypeFor(resolved.fileName.toString())
    }

    /**
     * Create a new file at [relativePath] under [root]. Create-only: fails if the target already
     * exists (callers map to 409).
     *
     * @throws FileExistsException if the target already exists.
     * @throws InvalidExchangePathException if the path escapes the boundary.
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
        resolved.parent?.let { Files.createDirectories(it) }

        // Write straight to the target with CREATE_NEW: the open atomically fails with
        // FileAlreadyExistsException when the file already exists (create-only, with no exists-check
        // TOCTOU). A mid-write failure is left to propagate as-is: deleting `resolved` here would race a
        // concurrent writer that just won CREATE_NEW on the same path and unlink ITS file.
        try {
            Files.write(resolved, bytes, StandardOpenOption.CREATE_NEW)
        } catch (e: FileAlreadyExistsException) {
            throw FileExistsException("File already exists: $relativePath")
        }

        // `resolved` is canonical (built from the resolver's real root), so relativize
        // against the canonical root to obtain the stored relative path.
        return toEntry(root.toRealPath(), resolved, scope)
    }

    /**
     * Delete [relativePath] under [root].
     *
     * @throws InvalidExchangePathException if the path escapes the scope boundary.
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
     * Read [resolved] fully into memory but never allocate more than the configured read cap: reads
     * at most [ExchangeStorageConfigProperties.readMaxSizeBytes] + 1 bytes, so a file that grows past
     * the cap *during* the read cannot cause an OutOfMemoryError (no size-check-then-read-all TOCTOU).
     * Throws [ExchangeFileTooLargeException] when the file exceeds the cap. Propagates
     * [java.nio.file.NoSuchFileException] (missing → 404) and other [IOException] (e.g. a directory →
     * 400) so the controller error mapping is unchanged.
     */
    private fun readWithinLimit(resolved: Path): ByteArray {
        val limit = config.readMaxSizeBytes
        val probe = (limit + 1).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val bytes = Files.newInputStream(resolved).use { it.readNBytes(probe) }
        if (bytes.size.toLong() > limit) {
            throw ExchangeFileTooLargeException("File is too large to read (exceeds the ${limit}-byte limit)")
        }
        return bytes
    }

    /**
     * Decode [bytes] as strict UTF-8, throwing [java.nio.charset.CharacterCodingException]
     * ([java.nio.charset.MalformedInputException]) on invalid input — matching [Files.readString] so
     * the controller keeps mapping a non-UTF-8 read to 400.
     */
    private fun decodeUtf8Strict(bytes: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    /**
     * Resolve [relativePath] under [root], guarding only against path traversal (the resolved
     * path must stay within [root]). No sensitive-file deny-list is applied — this is the
     * user-driven exchange, not LLM file access.
     */
    private fun resolveWithin(
        root: Path,
        relativePath: String,
    ): Path {
        if (relativePath.isBlank()) {
            throw InvalidExchangePathException("Invalid path: path must not be blank")
        }
        if (relativePath.split('/', '\\').any { it.length > MAX_SEGMENT_LENGTH }) {
            throw InvalidExchangePathException("Invalid path: path segment too long ($relativePath)")
        }
        // Does not create [root]: reads/deletes of a never-written scope surface as
        // NoSuchFileException (→ 404) instead of materialising empty shard directories. Writers
        // create the root before calling this (see writeNew).
        val canonicalRoot = root.toRealPath()
        val resolved =
            try {
                canonicalRoot.resolve(relativePath).normalize()
            } catch (e: java.nio.file.InvalidPathException) {
                // e.g. a NUL byte or other OS-illegal character in the path: a client bad-input case,
                // not a server error. InvalidPathException extends IllegalArgumentException, so map it
                // to the same 400 as the other path validations rather than letting it surface as 500.
                throw InvalidExchangePathException("Invalid path: illegal characters in '$relativePath'")
            }
        if (!resolved.startsWith(canonicalRoot)) {
            throw InvalidExchangePathException("Invalid path: path traversal not allowed ($relativePath)")
        }
        // normalize() is lexical and does not follow symlinks, so a symlink *inside* the root could
        // still point outside it. Canonicalize the deepest existing ancestor (the file itself for a
        // read/delete, the parent dir for a create) and require it to stay within the canonical root.
        val deepestExisting = generateSequence(resolved) { it.parent }.first(Files::exists)
        if (!deepestExisting.toRealPath().startsWith(canonicalRoot)) {
            throw InvalidExchangePathException("Invalid path: path traversal not allowed ($relativePath)")
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
