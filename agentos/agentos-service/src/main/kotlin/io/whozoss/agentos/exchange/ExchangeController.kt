package io.whozoss.agentos.exchange

import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.SchemaProperty
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.EntityType
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
 * Authorization is declared per method via `@PreAuthorize`, mirroring [io.whozoss.agentos.caseFlow.CaseController]
 * and [io.whozoss.agentos.namespace.NamespacePermissionEndpoints]:
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
) {
    // ========================================
    // Case scope
    // ========================================

    @GetMapping("/api/cases/{caseId}/files/manifest", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun getCaseFilesManifest(
        @PathVariable caseId: UUID,
    ): ExchangeManifest {
        val root = caseRootFor(caseId)
        val files = exchangeStorageService.listManifest(root, ExchangeScope.CASE)
        return ExchangeManifest(
            files,
            exchangeCapabilityService.capability(currentUserId(), EntityType.CASE, caseId.toString()),
        )
    }

    @GetMapping("/api/cases/{caseId}/files/content", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun getCaseFileContent(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeFileContent {
        val root = caseRootFor(caseId)
        return mapStorageErrors { exchangeStorageService.readContent(root, path) }
    }

    @GetMapping("/api/cases/{caseId}/files/download", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    fun downloadCaseFile(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ResponseEntity<ByteArray> {
        val root = caseRootFor(caseId)
        return downloadResponse(mapStorageErrors { exchangeStorageService.readBytes(root, path) }, path)
    }

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
    ): ExchangeFileEntry {
        val root = caseRootFor(caseId)
        val relativePath = uploadRelativePath(file)
        if (!exchangeStorageService.isUploadAllowed(relativePath)) {
            throw BadRequestException("File type not allowed for upload: '$relativePath'")
        }
        logger.info { "Uploading file '$relativePath' to case $caseId" }
        return mapStorageErrors { exchangeStorageService.writeNew(root, relativePath, file.bytes, ExchangeScope.CASE) }
    }

    @DeleteMapping("/api/cases/{caseId}/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    fun deleteCaseFile(
        @PathVariable caseId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse {
        val root = caseRootFor(caseId)
        mapStorageErrors { exchangeStorageService.delete(root, path) }
        logger.info { "Deleted file '$path' from case $caseId" }
        return ExchangeDeleteResponse(true, "Deleted $path")
    }

    // ========================================
    // Namespace scope (reads for any member; writes gated on Namespace WRITE = namespace admin / super-admin)
    // ========================================

    @GetMapping("/api/namespaces/{namespaceId}/files/manifest", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun getNamespaceFilesManifest(
        @PathVariable namespaceId: UUID,
    ): ExchangeManifest {
        val root = exchangeStorageService.namespaceRoot(namespaceId)
        val files = exchangeStorageService.listManifest(root, ExchangeScope.NAMESPACE)
        return ExchangeManifest(
            files,
            exchangeCapabilityService.capability(currentUserId(), EntityType.NAMESPACE, namespaceId.toString()),
        )
    }

    @GetMapping("/api/namespaces/{namespaceId}/files/content", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun getNamespaceFileContent(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ExchangeFileContent {
        val root = exchangeStorageService.namespaceRoot(namespaceId)
        return mapStorageErrors { exchangeStorageService.readContent(root, path) }
    }

    @GetMapping("/api/namespaces/{namespaceId}/files/download", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    @HideOnAccessDenied
    fun downloadNamespaceFile(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ResponseEntity<ByteArray> {
        val root = exchangeStorageService.namespaceRoot(namespaceId)
        return downloadResponse(mapStorageErrors { exchangeStorageService.readBytes(root, path) }, path)
    }

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
    ): ExchangeFileEntry {
        val root = exchangeStorageService.namespaceRoot(namespaceId)
        val relativePath = uploadRelativePath(file)
        if (!exchangeStorageService.isUploadAllowed(relativePath)) {
            throw BadRequestException("File type not allowed for upload: '$relativePath'")
        }
        logger.info { "Uploading file '$relativePath' to namespace $namespaceId" }
        return mapStorageErrors {
            exchangeStorageService.writeNew(root, relativePath, file.bytes, ExchangeScope.NAMESPACE)
        }
    }

    @DeleteMapping("/api/namespaces/{namespaceId}/files", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    fun deleteNamespaceFile(
        @PathVariable namespaceId: UUID,
        @RequestParam path: String,
    ): ExchangeDeleteResponse {
        val root = exchangeStorageService.namespaceRoot(namespaceId)
        mapStorageErrors { exchangeStorageService.delete(root, path) }
        logger.info { "Deleted file '$path' from namespace $namespaceId" }
        return ExchangeDeleteResponse(true, "Deleted $path")
    }

    // ========================================
    // Helpers
    // ========================================

    /**
     * Resolve the date-sharded case storage root. Loads the case once for its namespace and
     * immutable creation timestamp (the shard key); 404s when the case does not exist.
     */
    private fun caseRootFor(caseId: UUID): Path {
        val case = caseService.findById(caseId) ?: throw ResourceNotFoundException("Case not found: $caseId")
        return exchangeStorageService.caseRoot(case.namespaceId, caseId, case.metadata.created)
    }

    private fun currentUserId(): String = userService.getCurrentUser().id.toString()

    /** Relative path for an upload: the multipart filename (boundary resolution rejects traversal). */
    private fun uploadRelativePath(file: MultipartFile): String =
        file.originalFilename?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("Uploaded file must have a filename")

    /**
     * Translate storage-layer failures into the project's `@ResponseStatus` exceptions:
     * - [FileExistsException] / [FileAlreadyExistsException] (create-only collision, including the
     *   lost race of the atomic move) -> 409 [ConflictException]
     * - a missing target -> 404 [ResourceNotFoundException]: a read/delete of an absent file
     *   surfaces as [NoSuchFileException]; an `IllegalArgumentException("Path does not exist: ...")`
     *   is also mapped to 404 (defensive).
     * - a non-UTF-8 file read -> 400 [BadRequestException] ([CharacterCodingException]).
     * - any other [IllegalArgumentException] (traversal, over-long or illegal path) -> 400.
     */
    private fun <T> mapStorageErrors(block: () -> T): T =
        try {
            block()
        } catch (e: FileExistsException) {
            throw ConflictException(e.message ?: "File already exists", e)
        } catch (e: FileAlreadyExistsException) {
            throw ConflictException(e.message ?: "File already exists", e)
        } catch (e: NoSuchFileException) {
            throw ResourceNotFoundException(e.message ?: "File not found")
        } catch (e: CharacterCodingException) {
            throw BadRequestException("File is not valid UTF-8 text and cannot be displayed")
        } catch (e: IOException) {
            // e.g. "Is a directory" when the path targets a folder, or other non-file I/O errors.
            throw BadRequestException(e.message ?: "Invalid file operation")
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("Path does not exist") == true) {
                throw ResourceNotFoundException(e.message ?: "File not found")
            }
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
        // switch to RFC 5987 `filename*=UTF-8''…` for names with non-ASCII chars (accents, CJK,
        // emoji) that the ISO-8859-1 header encoding would otherwise mangle.
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

/** Response body for a successful exchange file deletion. */
@Schema(name = "ExchangeDeleteResponse", description = "Result of an exchange file deletion.")
data class ExchangeDeleteResponse(
    @field:Schema(description = "Whether the deletion succeeded.")
    val success: Boolean,
    @field:Schema(description = "Human-readable outcome message.")
    val message: String,
)
