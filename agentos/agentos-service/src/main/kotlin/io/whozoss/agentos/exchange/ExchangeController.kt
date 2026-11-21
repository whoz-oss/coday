package io.whozoss.agentos.exchange

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.sdk.api.exchange.ExchangeApi
import io.whozoss.agentos.sdk.api.exchange.ExchangeCapability
import io.whozoss.agentos.sdk.api.exchange.ExchangeDeleteResponse
import io.whozoss.agentos.sdk.api.exchange.ExchangeDirectoryEntry
import io.whozoss.agentos.sdk.api.exchange.ExchangeDirectoryListing
import io.whozoss.agentos.sdk.api.exchange.ExchangeFileContent
import io.whozoss.agentos.sdk.api.exchange.ExchangeFileEntry
import io.whozoss.agentos.sdk.api.exchange.ExchangeManifest
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.util.UUID

/**
 * REST API for the AgentOS file exchange: case-private and namespace-shared files.
 *
 * A standalone `@RestController` (no class-level `@RequestMapping`) so the case and
 * namespace resources keep their own URL trees (`/api/cases/...`, `/api/namespaces/...`).
 *
 * Implements [ExchangeApi] so external consumers can declare a Feign client against the SDK
 * interface. Spring MVC annotations (`@GetMapping`, `@PreAuthorize`, etc.) live here and are
 * intentionally absent from the interface.
 *
 * Authorization is declared per method via `@PreAuthorize`:
 * - case reads gate on Case READ, case writes on Case WRITE
 * - namespace reads gate on Namespace READ, namespace writes on Namespace WRITE (= namespace admin)
 *
 * Read (`GET`) endpoints carry [HideOnAccessDenied] so a denied check returns 404 (hide
 * existence) instead of 403, matching the read endpoints on the entity controllers.
 *
 * Every manifest embeds a server-computed [ExchangeCapability] (fail-closed). The caller
 * already holds READ (guaranteed by `@PreAuthorize`); the capability service upgrades to `READ_WRITE`
 * only when the permission model grants write. Clients must read `manifest.capability`
 * rather than infer it.
 */
@RestController
class ExchangeController(
    private val exchangeStorageService: ExchangeStorageService,
    private val caseService: CaseService,
    private val exchangeCapabilityService: ExchangeCapabilityService,
    private val userService: UserService,
    private val exchangeRootResolver: ExchangeRootResolver,
) : ExchangeApi {
    // ========================================
    // Case scope
    // ========================================

    @GetMapping("/api/cases/{caseId}/files/manifest", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getCaseFilesManifest(
        @PathVariable caseId: UUID,
    ): ExchangeManifest {
        val root = resolvedCase(caseId)
        val manifest = manifestFor(root.requireUsable(), ExchangeScope.CASE, EntityType.CASE, caseId)
        return if (root.ownerCaseId == caseId) manifest else manifest.copy(capability = sharedCapability(caseId, root))
    }

    /**
     * Browse one directory level of a case's files.
     *
     * Additive, and deliberately not part of the [ExchangeApi] contract: adding an abstract method
     * to an interface that external consumers implement would break them at startup.
     */
    @GetMapping("/api/cases/{caseId}/files/directory", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun browseCaseFiles(
        @PathVariable caseId: UUID,
        @RequestParam(required = false, defaultValue = "") path: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "200") size: Int,
    ): ExchangeDirectoryListing {
        val root = resolvedCase(caseId)
        val listing = listingFor(root.requireUsable(), path, page, size, EntityType.CASE, caseId)
        return if (root.ownerCaseId == caseId) listing else listing.copy(capability = sharedCapability(caseId, root))
    }

    @GetMapping("/api/cases/{caseId}/files/content", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getCaseFileContent(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeFileContent = contentFor(caseRootFor(caseId), path)

    @GetMapping("/api/cases/{caseId}/files/download", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun downloadCaseFile(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ResponseEntity<ByteArray> = downloadFor(caseRootFor(caseId), path)

    @SwaggerRequestBody(
        content = [
            Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = Schema(type = "object", requiredProperties = ["file"]),
                schemaProperties = [SchemaProperty(name = "file", schema = Schema(type = "string", format = "binary"))],
            ),
        ],
    )
    @PostMapping(
        "/api/cases/{caseId}/files",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun uploadCaseFile(
        @PathVariable caseId: UUID,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false, defaultValue = "") directory: String,
    ): ExchangeFileEntry =
        withFileMutation(caseId) { root -> uploadTo(root, ExchangeScope.CASE, file, directory, "case $caseId") }

    /**
     * Create a folder in a case's files. Additive, and not part of the [ExchangeApi] contract (see [browseCaseFiles]).
     */
    @PostMapping("/api/cases/{caseId}/files/directory", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun createCaseDirectory(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeDirectoryEntry = withFileMutation(caseId) { root -> createDirectoryIn(root, path, "case $caseId") }

    /** Delete a folder of a case's files, with everything in it. */
    @DeleteMapping("/api/cases/{caseId}/files/directory", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun deleteCaseDirectory(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse = withFileMutation(caseId) { root -> deleteDirectoryFrom(root, path, "case $caseId") }

    /** Download a folder of a case's files (empty path: all of them) as one ZIP. */
    @GetMapping("/api/cases/{caseId}/files/archive", produces = [ZIP_MEDIA_TYPE])
    @ApiResponse(responseCode = "200", description = "OK", content = [Content(mediaType = ZIP_MEDIA_TYPE, schema = Schema(type = "string", format = "binary"))])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun downloadCaseArchive(
        @PathVariable caseId: UUID,
        @RequestParam(required = false, defaultValue = "") path: String,
    ): ResponseEntity<StreamingResponseBody> = archiveFor(resolvedCase(caseId).requireUsable(), path, "case-files")

    @DeleteMapping("/api/cases/{caseId}/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun deleteCaseFile(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse = withFileMutation(caseId) { root -> deleteFrom(root, path, "case $caseId") }

    // ========================================
    // Namespace scope (reads for any member; writes gated on Namespace WRITE = namespace admin / super-admin)
    // ========================================

    @GetMapping("/api/namespaces/{namespaceId}/files/manifest", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    override fun getNamespaceFilesManifest(
        @PathVariable namespaceId: UUID,
    ): ExchangeManifest =
        manifestFor(
            exchangeStorageService.namespaceRoot(namespaceId),
            ExchangeScope.NAMESPACE,
            EntityType.NAMESPACE,
            namespaceId,
        )

    /** Browse one directory level of a namespace's shared files. */
    @GetMapping("/api/namespaces/{namespaceId}/files/directory", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun browseNamespaceFiles(
        @PathVariable namespaceId: UUID,
        @RequestParam(required = false, defaultValue = "") path: String,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "200") size: Int,
    ): ExchangeDirectoryListing =
        listingFor(
            exchangeStorageService.namespaceRoot(namespaceId),
            path,
            page,
            size,
            EntityType.NAMESPACE,
            namespaceId,
        )

    @GetMapping("/api/namespaces/{namespaceId}/files/content", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    override fun getNamespaceFileContent(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ExchangeFileContent = contentFor(exchangeStorageService.namespaceRoot(namespaceId), path)

    @GetMapping("/api/namespaces/{namespaceId}/files/download", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun downloadNamespaceFile(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ResponseEntity<ByteArray> = downloadFor(exchangeStorageService.namespaceRoot(namespaceId), path)

    @SwaggerRequestBody(
        content = [
            Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = Schema(type = "object", requiredProperties = ["file"]),
                schemaProperties = [SchemaProperty(name = "file", schema = Schema(type = "string", format = "binary"))],
            ),
        ],
    )
    @PostMapping(
        "/api/namespaces/{namespaceId}/files",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun uploadNamespaceFile(
        @PathVariable namespaceId: UUID,
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false, defaultValue = "") directory: String,
    ): ExchangeFileEntry =
        uploadTo(
            exchangeStorageService.namespaceRoot(namespaceId),
            ExchangeScope.NAMESPACE,
            file,
            directory,
            "namespace $namespaceId",
        )

    /** Create a folder in a namespace's shared files. */
    @PostMapping("/api/namespaces/{namespaceId}/files/directory", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun createNamespaceDirectory(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ExchangeDirectoryEntry =
        createDirectoryIn(exchangeStorageService.namespaceRoot(namespaceId), path, "namespace $namespaceId")

    /** Delete a folder of a namespace's shared files, with everything in it. */
    @DeleteMapping("/api/namespaces/{namespaceId}/files/directory", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun deleteNamespaceDirectory(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse =
        deleteDirectoryFrom(exchangeStorageService.namespaceRoot(namespaceId), path, "namespace $namespaceId")

    /** Download a folder of a namespace's shared files (empty path: all of them) as one ZIP. */
    @GetMapping("/api/namespaces/{namespaceId}/files/archive", produces = [ZIP_MEDIA_TYPE])
    @ApiResponse(responseCode = "200", description = "OK", content = [Content(mediaType = ZIP_MEDIA_TYPE, schema = Schema(type = "string", format = "binary"))])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun downloadNamespaceArchive(
        @PathVariable namespaceId: UUID,
        @RequestParam(required = false, defaultValue = "") path: String,
    ): ResponseEntity<StreamingResponseBody> =
        archiveFor(exchangeStorageService.namespaceRoot(namespaceId), path, "namespace-files")

    @DeleteMapping("/api/namespaces/{namespaceId}/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    override fun deleteNamespaceFile(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse =
        deleteFrom(exchangeStorageService.namespaceRoot(namespaceId), path, "namespace $namespaceId")

    // ========================================
    // Helpers
    // ========================================

    /**
     * One page of one directory level, with the caller's capability attached.
     *
     * Bounds the requested page size: an unbounded one would let a client ask for the whole of a
     * repository in a single response, which is the problem this endpoint exists to avoid.
     */
    private fun listingFor(
        root: Path,
        path: String,
        page: Int,
        size: Int,
        entityType: EntityType,
        entityId: UUID,
    ): ExchangeDirectoryListing {
        val pageSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val (entries, total) =
            mapStorageErrors {
                try {
                    exchangeStorageService.listDirectory(root, path, safePage, pageSize)
                } catch (e: NotDirectoryException) {
                    throw BadRequestException("Not a directory")
                }
            }
        return ExchangeDirectoryListing(
            path = path.trim().trim('/'),
            entries = entries,
            totalEntries = total,
            page = safePage,
            pageSize = pageSize,
            hasMore = (safePage.toLong() + 1) * pageSize < total,
            capability = exchangeCapabilityService.capability(currentUserId(), entityType, entityId.toString()),
        )
    }

    private fun manifestFor(
        root: Path,
        scope: ExchangeScope,
        entityType: EntityType,
        entityId: UUID,
    ): ExchangeManifest =
        ExchangeManifest(
            exchangeStorageService.listManifest(root, scope),
            exchangeCapabilityService.capability(currentUserId(), entityType, entityId.toString()),
        )

    /** Read a file's text content under [root], mapping storage failures to HTTP statuses. */
    private fun contentFor(
        root: Path,
        path: String,
    ): ExchangeFileContent = mapStorageErrors { exchangeStorageService.readContent(root, path) }

    /** Stream a file's raw bytes under [root] as an attachment download. */
    private fun downloadFor(
        root: Path,
        path: String,
    ): ResponseEntity<ByteArray> =
        downloadResponse(mapStorageErrors { exchangeStorageService.readBytes(root, path) }, path)

    /**
     * Validate the upload against the extension allow-list, then create the file under [root], inside
     * [directory] (relative to the root; empty for the root itself).
     */
    private fun uploadTo(
        root: Path,
        scope: ExchangeScope,
        file: MultipartFile,
        directory: String,
        logTarget: String,
    ): ExchangeFileEntry {
        val relativePath =
            listOf(directory.trim().trim('/'), uploadRelativePath(file))
                .filter { it.isNotEmpty() }
                .joinToString("/")
        if (!exchangeStorageService.isUploadAllowed(relativePath)) {
            throw BadRequestException("File type not allowed for upload: '$relativePath'")
        }
        logger.info { "Uploading file '$relativePath' to $logTarget" }
        return mapStorageErrors { exchangeStorageService.writeNew(root, relativePath, file.bytes, scope) }
    }

    /** Delete a file under [root], mapping storage failures to HTTP statuses. */
    private fun deleteFrom(
        root: Path,
        path: String,
        logTarget: String,
    ): ExchangeDeleteResponse {
        mapStorageErrors { exchangeStorageService.delete(root, path) }
        logger.info { "Deleted file '$path' from $logTarget" }
        return ExchangeDeleteResponse(true, "Deleted $path")
    }

    /** Create a folder under [root], mapping storage failures to HTTP statuses. */
    private fun createDirectoryIn(
        root: Path,
        path: String,
        logTarget: String,
    ): ExchangeDirectoryEntry {
        val created = mapStorageErrors { exchangeStorageService.createDirectory(root, path) }
        logger.info { "Created folder '${created.path}' in $logTarget" }
        return created
    }

    /** Delete a folder and its content under [root], mapping storage failures to HTTP statuses. */
    private fun deleteDirectoryFrom(
        root: Path,
        path: String,
        logTarget: String,
    ): ExchangeDeleteResponse {
        mapStorageErrors { exchangeStorageService.deleteDirectory(root, path) }
        logger.info { "Deleted folder '$path' from $logTarget" }
        return ExchangeDeleteResponse(true, "Deleted $path")
    }

    /**
     * One ZIP of the folder [path] under [root]. The folder is checked before the response starts, so a missing
     * or invalid one is a regular HTTP error; the files are then streamed, never held in memory.
     * [rootName] names the archive when [path] is the scope root.
     */
    private fun archiveFor(
        root: Path,
        path: String,
        rootName: String,
    ): ResponseEntity<StreamingResponseBody> {
        mapStorageErrors { exchangeStorageService.assertArchivable(root, path) }
        val folderName = path.trim().trim('/').let { if (it.isEmpty()) rootName else dispositionFilename(it) }
        val body = StreamingResponseBody { out -> exchangeStorageService.writeArchive(root, path, out) }
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition("$folderName.zip"))
            .contentType(MediaType.parseMediaType(ZIP_MEDIA_TYPE))
            .body(body)
    }

    /**
     * Where this case's files live; 404 when the case does not exist.
     *
     * Goes through [ExchangeRootResolver] so REST and the agent tools always agree. `@PreAuthorize`
     * already checked the case itself; a directory owned by another case (shared storage) also
     * requires access to that owner, since sharing a directory never grants its owner's rights.
     */
    private fun resolvedCase(caseId: UUID): ResolvedExchangeRoot =
        exchangeRootResolver.resolve(findCase(caseId)).also { requireOwnerAccess(caseId, it, Action.READ) }

    private fun <T> withFileMutation(
        caseId: UUID,
        action: (Path) -> T,
    ): T =
        exchangeRootResolver.withFileMutation(findCase(caseId)) { root ->
            requireOwnerAccess(caseId, root, Action.WRITE)
            action(root.requireUsable())
        }

    private fun findCase(caseId: UUID) = caseService.findById(caseId) ?: throw ResourceNotFoundException("Case not found: $caseId")

    private fun caseRootFor(caseId: UUID): Path = resolvedCase(caseId).requireUsable()

    private fun requireOwnerAccess(
        caseId: UUID,
        root: ResolvedExchangeRoot,
        action: Action,
    ) {
        if (root.ownerCaseId != caseId) exchangeCapabilityService.requireCaseAccess(currentUserId(), caseId, root, action)
    }

    private fun sharedCapability(
        caseId: UUID,
        root: ResolvedExchangeRoot,
    ) = exchangeCapabilityService.caseCapability(currentUserId(), caseId, root)

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    /**
     * Relative path for an upload: the multipart filename as-is. This method does not sanitise it;
     * any traversal in the name is caught downstream by [ExchangeStorageService] path resolution,
     * which confines the write to the scope root.
     */
    private fun uploadRelativePath(file: MultipartFile): String =
        file.originalFilename?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("Uploaded file must have a filename")

    /**
     * Translate storage-layer failures into the project's `@ResponseStatus` exceptions:
     * - [FileExistsException] / [FileAlreadyExistsException] (create-only collision) -> 409 [ConflictException]
     * - a missing target -> 404 [ResourceNotFoundException] ([NoSuchFileException]).
     * - a non-UTF-8 file read -> 400 [BadRequestException] ([CharacterCodingException]).
     * - a file above the read limit -> 422 [UnprocessableEntityException] ([ExchangeFileTooLargeException]).
     * - an invalid path (traversal, over-long segment, blank, illegal chars) -> 400 [BadRequestException]
     *   ([InvalidExchangePathException]).
     * - any other I/O failure -> 400 [BadRequestException] ([IOException]; generic message, since the
     *   raw one carries the absolute path).
     */
    private fun <T> mapStorageErrors(block: () -> T): T =
        try {
            block()
        } catch (e: FileExistsException) {
            logger.info(e) { "Exchange conflict (FileExistsException): ${e.message}" }
            throw ConflictException(e.message ?: "File already exists", e)
        } catch (e: FileAlreadyExistsException) {
            // Generic message on purpose: a raw FileAlreadyExistsException carries the absolute server
            // path and must not leak in the 409 body.
            logger.info(e) { "Exchange conflict (FileAlreadyExistsException): ${e.message}" }
            throw ConflictException("File already exists", e)
        } catch (e: NoSuchFileException) {
            // Generic message on purpose: NoSuchFileException.message is the absolute server path.
            logger.warn(e) { "Exchange file not found (NoSuchFileException): ${e.message}" }
            throw ResourceNotFoundException("File not found")
        } catch (e: CharacterCodingException) {
            logger.warn(e) { "Exchange file encoding error (CharacterCodingException)" }
            throw BadRequestException("File is not valid UTF-8 text and cannot be displayed")
        } catch (e: ExchangeFileTooLargeException) {
            logger.warn(e) { "Exchange file too large: ${e.message}" }
            throw UnprocessableEntityException(e.message ?: "File is too large to read")
        } catch (e: ReservedEntryConflictException) {
            logger.info(e) { "Exchange conflict (ReservedEntryConflictException): ${e.message}" }
            throw ConflictException("Folder contains protected entries", e)
        } catch (e: NotDirectoryException) {
            logger.warn(e) { "Exchange path is not a directory: ${e.message}" }
            throw BadRequestException("Not a directory")
        } catch (e: IOException) {
            // Generic message on purpose: a raw FileSystemException carries the absolute server path.
            logger.warn(e) { "Exchange I/O error (${e::class.simpleName}): ${e.message}" }
            throw BadRequestException("Invalid file operation")
        } catch (e: InvalidExchangePathException) {
            logger.warn(e) { "Exchange invalid path: ${e.message}" }
            throw BadRequestException(e.message ?: "Invalid path")
        }

    /** Build an attachment download response, deriving a header-safe filename from [path]. */
    private fun downloadResponse(
        payload: Pair<ByteArray, String?>,
        path: String,
    ): ResponseEntity<ByteArray> {
        val (bytes, mimeType) = payload
        val contentType =
            mimeType?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
                ?: MediaType.APPLICATION_OCTET_STREAM
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, attachmentDisposition(dispositionFilename(path)))
            .contentType(contentType)
            .body(bytes)
    }

    /**
     * `Content-Disposition: attachment` for [filename]. Keeps a clean `filename="report.txt"` for ASCII names
     * (interoperable everywhere); only switches to RFC 5987 `filename*=UTF-8''...` for names with non-ASCII chars.
     */
    private fun attachmentDisposition(filename: String): String {
        val isAscii = filename.all { it.code in 0x20..0x7E }
        return ContentDisposition
            .attachment()
            .let { if (isAscii) it.filename(filename) else it.filename(filename, StandardCharsets.UTF_8) }
            .build()
            .toString()
    }

    /**
     * Leaf filename safe for a `Content-Disposition` header: drop any path components and
     * strip quotes and CR/LF so a crafted path cannot inject header tokens.
     */
    private fun dispositionFilename(path: String): String =
        path
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace("\"", "")
            .replace("\r", "")
            .replace("\n", "")
            .ifBlank { "download" }

    companion object : KLogging() {
        /** Upper bound on a requested page, so one call cannot ask for a whole repository. */
        private const val MAX_PAGE_SIZE = 500

        private const val ZIP_MEDIA_TYPE = "application/zip"
    }
}
