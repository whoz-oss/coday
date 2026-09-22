package io.whozoss.agentos.exchange

import io.whozoss.agentos.sdk.api.exchange.ExchangeDirectoryEntry
import io.whozoss.agentos.sdk.api.exchange.ExchangeFileContent
import io.whozoss.agentos.sdk.api.exchange.ExchangeFileEntry
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
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
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
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

        /** Git metadata directory (or, in a linked worktree, pointer file). Never exposed via CRUD. */
        private const val GIT_METADATA_DIR = ".git"

        // Cap manifest traversal depth (matches the file-plugin's SearchFilesTool) so a deeply
        // nested exchange tree can't turn a manifest request into an unbounded walk.
        private const val MANIFEST_MAX_DEPTH = 20
    }

    private val mountRoot: Path = Path.of(config.mountRoot)

    /** Internal bare repository, outside both browsable Exchange roots. */
    fun namespaceGitDirectory(namespaceId: UUID): Path = mountRoot.resolve(namespaceId.toString()).resolve("repository.git")

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
                /**
                 * Skip git metadata wholesale. Besides keeping `.git` out of a listing that users
                 * can act on, this avoids walking the object store of an associated namespace,
                 * which is where the overwhelming majority of a repository's files live.
                 */
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    when {
                        dir.fileName?.toString().equals(GIT_METADATA_DIR, ignoreCase = true) -> FileVisitResult.SKIP_SUBTREE
                        else -> FileVisitResult.CONTINUE
                    }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // A linked worktree's `.git` is a regular file, not a directory.
                    if (file.fileName?.toString().equals(GIT_METADATA_DIR, ignoreCase = true)) {
                        return FileVisitResult.CONTINUE
                    }
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
        val resolved = resolveReadPath(root, relativePath)
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
        val resolved = resolveReadPath(root, relativePath)
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
            logger.warn(e) { "File '$relativePath' already exists" }
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
    /** Compatibility for relative links written before the offline repo/ migration. */
    private fun resolveReadPath(root: Path, relativePath: String): Path {
        val direct = resolveWithin(root, relativePath)
        if (Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) return direct
        var ancestor: Path? = root.toAbsolutePath().normalize().parent
        val mount = mountRoot.toAbsolutePath().normalize()
        repeat(6) {
            val current = ancestor ?: return direct
            if (!current.startsWith(mount)) return direct
            if (Files.isRegularFile(current.resolve(".exchange-repo-migration.json"))) {
                val legacy = resolveWithin(root, "repo/$relativePath")
                return if (Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)) legacy else direct
            }
            ancestor = current.parent
        }
        return direct
    }

    private fun readWithinLimit(resolved: Path): ByteArray {
        val limit = config.readMaxSizeBytes
        val probe = (limit + 1).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val bytes = Files.newInputStream(resolved).use { it.readNBytes(probe) }
        if (bytes.size.toLong() > limit) {
            throw ExchangeFileTooLargeException("File is too large to read (exceeds the $limit-byte limit)")
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
        assertNotGitMetadata(relativePath.split('/', '\\'), relativePath)
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
        val canonicalTarget = deepestExisting.toRealPath()
        if (!canonicalTarget.startsWith(canonicalRoot)) {
            throw InvalidExchangePathException("Invalid path: path traversal not allowed ($relativePath)")
        }
        // Re-check on the canonical path, not just the requested one: a symlink inside the root
        // (`docs -> .git`) resolves to a target that is legitimately within the root, so the
        // lexical check above would not see the git metadata it aliases.
        assertNotGitMetadata(canonicalRoot.relativize(canonicalTarget).map { it.toString() }, relativePath)
        return resolved
    }

    /**
     * Refuse any path that reaches git metadata.
     *
     * Equipped case Exchanges contain a linked worktree in `repo/`, so its `.git` pointer
     * sits inside a browsable scope. Generic CRUD must not reach it: deleting the directory
     * (or, in a linked worktree, the `.git` pointer file) detaches the worktree, rewriting the
     * pointer redirects later server-side commands at another worktree, and reading `config`
     * discloses remote URLs and absolute server paths. Git itself keeps working — this guards the
     * file-management API, not the commands the provisioner runs.
     *
     * Applied to every scope, not only associated ones: nothing legitimately manages a `.git`
     * entry through this API, so the rule needs no knowledge of whether Git is configured.
     */
    private fun assertNotGitMetadata(
        segments: List<String>,
        relativePath: String,
    ) {
        // Case-insensitive because a case-insensitive filesystem (macOS, Windows) would otherwise
        // let `.GIT/config` alias the same directory.
        if (segments.any { it.equals(GIT_METADATA_DIR, ignoreCase = true) }) {
            throw InvalidExchangePathException("Invalid path: git metadata is not accessible ($relativePath)")
        }
    }

    /**
     * List one directory level under [root], sorted and paginated.
     *
     * The companion to [listManifest], and the only listing usable on a repository: a checkout with
     * its dependencies holds tens of thousands of files, which is neither readable as a flat list
     * nor cheap to walk on every open. Here the cost is bounded by the size of one directory.
     *
     * [relativePath] is the directory to list; empty (or `/`) means the scope root itself. Entries
     * are sorted with directories first, then by name, which is what a browser shows.
     *
     * Symlinks are skipped, matching [listManifest]: attributes are read without following them, so
     * an entry that is neither a regular file nor a directory is dropped rather than followed out
     * of the scope. Git metadata is excluded, as everywhere else in this API.
     *
     * @return the page of entries and the total number of entries in the directory.
     * @throws InvalidExchangePathException if the path escapes the scope or reaches git metadata.
     * @throws java.nio.file.NoSuchFileException if the directory does not exist.
     * @throws java.nio.file.NotDirectoryException if the path denotes a file.
     */
    fun listDirectory(
        root: Path,
        relativePath: String,
        page: Int,
        pageSize: Int,
    ): Pair<List<ExchangeDirectoryEntry>, Int> {
        val normalizedRequest = relativePath.trim().trim('/')
        val directory =
            when {
                normalizedRequest.isEmpty() -> root
                else -> resolveWithin(root, normalizedRequest)
            }

        // A scope nobody has written to yet is empty, not an error — same contract as the manifest.
        if (!Files.exists(root)) return emptyList<ExchangeDirectoryEntry>() to 0
        if (!Files.exists(directory)) throw NoSuchFileException(normalizedRequest)
        if (!Files.isDirectory(directory)) throw NotDirectoryException(normalizedRequest)

        // Both sides must be canonical, and the stream must come from the canonical directory:
        // on macOS `/var` is a symlink to `/private/var`, so relativising entries yielded by a
        // non-canonical directory against a canonical root produces a path full of `..`.
        val canonicalRoot = root.toRealPath()
        val canonicalDirectory = directory.toRealPath()
        val all =
            Files.newDirectoryStream(canonicalDirectory).use { stream ->
                stream.mapNotNull { entry -> toDirectoryEntry(canonicalRoot, entry) }
            }

        val sorted = all.sortedWith(compareByDescending<ExchangeDirectoryEntry> { it.directory }.thenBy { it.name.lowercase() })
        val from = (page.coerceAtLeast(0).toLong() * pageSize.coerceAtLeast(1)).coerceAtMost(sorted.size.toLong()).toInt()
        return sorted.drop(from).take(pageSize.coerceAtLeast(1)) to sorted.size
    }

    /**
     * Map one directory child, or null when it must not be listed.
     *
     * Attributes are read with [LinkOption.NOFOLLOW_LINKS] so a symlink is recognised as such and
     * dropped, rather than silently resolved — possibly to a target outside the scope.
     */
    private fun toDirectoryEntry(
        canonicalRoot: Path,
        entry: Path,
    ): ExchangeDirectoryEntry? {
        val name = entry.fileName?.toString() ?: return null
        if (name.equals(GIT_METADATA_DIR, ignoreCase = true)) return null

        val attributes =
            runCatching {
                Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrElse { e ->
                // A concurrent delete is expected; anything else is worth a line but not a failure
                // of the whole listing.
                if (e !is NoSuchFileException) logger.warn(e) { "Skipping exchange entry: $entry" }
                return null
            }
        if (!attributes.isRegularFile && !attributes.isDirectory) return null

        val relative = canonicalRoot.relativize(entry).joinToString("/") { it.toString() }
        return ExchangeDirectoryEntry(
            path = relative,
            name = name,
            directory = attributes.isDirectory,
            size = attributes.size().takeUnless { attributes.isDirectory },
            lastModified = attributes.lastModifiedTime().toInstant(),
            mimeType = if (attributes.isDirectory) null else mimeTypeFor(name),
        )
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
    private fun mimeTypeFor(filename: String): String? = URLConnection.guessContentTypeFromName(filename) ?: textMimeFallback(filename)

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
