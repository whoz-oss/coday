package io.whozoss.agentos.exchange

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.sdk.api.exchange.ExchangeApi
import io.whozoss.agentos.sdk.api.exchange.ExchangeCapability
import io.whozoss.agentos.sdk.api.exchange.ExchangeDeleteResponse
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
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
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
) : ExchangeApi {
    // ========================================
    // Case scope
    // ========================================

    @GetMapping("/api/cases/{caseId}/files/manifest", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun getCaseFilesManifest(
        @PathVariable caseId: UUID,
    ): ExchangeManifest = manifestFor(caseRootFor(caseId), ExchangeScope.CASE, EntityType.CASE, caseId)

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
    ): ExchangeFileEntry = uploadTo(caseRootFor(caseId), ExchangeScope.CASE, file, "case $caseId")

    @DeleteMapping("/api/cases/{caseId}/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun deleteCaseFile(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse = deleteFrom(caseRootFor(caseId), path, "case $caseId")

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
    ): ExchangeFileEntry =
        uploadTo(
            exchangeStorageService.namespaceRoot(namespaceId),
            ExchangeScope.NAMESPACE,
            file,
            "namespace $namespaceId",
        )

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

    /** Build the manifest for a scope root, embedding the caller's server-computed capability. */
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

    /** Validate the upload against the extension allow-list, then create the file under [root]. */
    private fun uploadTo(
        root: Path,
        scope: ExchangeScope,
        file: MultipartFile,
        logTarget: String,
    ): ExchangeFileEntry {
        val relativePath = uploadRelativePath(file)
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

    /**
     * Resolve the date-sharded case storage root. Loads the case once for its namespace and
     * immutable creation timestamp (the shard key); 404s when the case does not exist.
     */
    private fun caseRootFor(caseId: UUID): Path {
        val case = caseService.findById(caseId) ?: throw ResourceNotFoundException("Case not found: $caseId")
        return exchangeStorageService.caseRoot(case.namespaceId, caseId, case.metadata.created)
    }

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
            throw ConflictException(e.message ?: "File already exists", e)
        } catch (e: FileAlreadyExistsException) {
            // Generic message on purpose: a raw FileAlreadyExistsException carries the absolute server
            // path and must not leak in the 409 body.
            throw ConflictException("File already exists", e)
        } catch (e: NoSuchFileException) {
            // Generic message on purpose: NoSuchFileException.message is the absolute server path.
            throw ResourceNotFoundException("File not found")
        } catch (e: CharacterCodingException) {
            throw BadRequestException("File is not valid UTF-8 text and cannot be displayed")
        } catch (e: ExchangeFileTooLargeException) {
            throw UnprocessableEntityException(e.message ?: "File is too large to read")
        } catch (e: IOException) {
            // Generic message on purpose: a raw FileSystemException carries the absolute server path.
            throw BadRequestException("Invalid file operation")
        } catch (e: InvalidExchangePathException) {
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
        // Keep a clean `filename="report.txt"` for ASCII names (interoperable everywhere); only
        // switch to RFC 5987 `filename*=UTF-8''...` for names with non-ASCII chars.
        val filename = dispositionFilename(path)
        val isAscii = filename.all { it.code in 0x20..0x7E }
        val disposition =
            ContentDisposition
                .attachment()
                .let { if (isAscii) it.filename(filename) else it.filename(filename, StandardCharsets.UTF_8) }
                .build()
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .contentType(contentType)
            .body(bytes)
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

    companion object : KLogging()
}
