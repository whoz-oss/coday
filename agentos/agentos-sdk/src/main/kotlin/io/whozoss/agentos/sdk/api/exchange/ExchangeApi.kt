package io.whozoss.agentos.sdk.api.exchange

import io.swagger.v3.oas.annotations.Operation
import java.util.UUID

/**
 * HTTP API contract for the AgentOS file exchange: case-private and namespace-shared files.
 *
 * Implemented by `ExchangeController` in agentos-service (a standalone `@RestController` with no
 * class-level `@RequestMapping`; Spring MVC annotations live exclusively on the controller).
 * External consumers (e.g. the whoz Copilot module) implement this interface as a Feign client,
 * adding their own `@FeignClient` and routing annotations.
 *
 * **Two scopes are exposed:**
 * - **Case scope** — files private to a single case (`/api/cases/{caseId}/files/...`).
 *   Reads require Case `READ`; writes require Case `WRITE`.
 * - **Namespace scope** — files shared across a namespace (`/api/namespaces/{namespaceId}/files/...`).
 *   Reads require Namespace `READ`; writes require Namespace `WRITE` (namespace admin / super-admin).
 *
 * **Download** (`/download`) and **upload** (`POST /files`) are intentionally absent from this
 * interface: download returns `ResponseEntity<ByteArray>` and upload is a multipart request — both
 * require consumer-specific HTTP client configuration that this interface should not prescribe.
 * Consumers should call those URLs directly.
 */
interface ExchangeApi {

    // ========================================
    // Case scope
    // ========================================

    /**
     * GET /api/cases/{caseId}/files/manifest
     *
     * Lists all files in the case-private exchange scope and returns the caller's server-computed
     * [ExchangeCapability]. The capability is fail-closed: the caller already holds `READ`
     * (guaranteed by the `@PreAuthorize` gate on the controller); the server upgrades to
     * `READ_WRITE` only when the permission model grants Case `WRITE`.
     */
    @Operation(
        summary = "List case exchange files",
        description = "GET /api/cases/{caseId}/files/manifest — list all files in the case-private exchange scope.",
    )
    fun getCaseFilesManifest(caseId: UUID): ExchangeManifest

    /**
     * GET /api/cases/{caseId}/files/content?path=
     *
     * Returns the UTF-8 text content of a single file. The server rejects non-UTF-8 files with 400.
     */
    @Operation(
        summary = "Read case exchange file content",
        description = "GET /api/cases/{caseId}/files/content — read the UTF-8 text content of a case exchange file.",
    )
    fun getCaseFileContent(
        caseId: UUID,
        path: String,
    ): ExchangeFileContent

    /**
     * DELETE /api/cases/{caseId}/files?path=
     *
     * Deletes a single file from the case-private exchange scope.
     */
    @Operation(
        summary = "Delete case exchange file",
        description = "DELETE /api/cases/{caseId}/files — delete a file from the case-private exchange scope.",
    )
    fun deleteCaseFile(
        caseId: UUID,
        path: String,
    ): ExchangeDeleteResponse

    // ========================================
    // Namespace scope
    // ========================================

    /**
     * GET /api/namespaces/{namespaceId}/files/manifest
     *
     * Lists all files in the namespace-shared exchange scope and returns the caller's
     * server-computed [ExchangeCapability]. Any namespace member can read; only namespace
     * admins (Namespace `WRITE`) receive `READ_WRITE`.
     */
    @Operation(
        summary = "List namespace exchange files",
        description = "GET /api/namespaces/{namespaceId}/files/manifest — list all files in the namespace-shared exchange scope.",
    )
    fun getNamespaceFilesManifest(namespaceId: UUID): ExchangeManifest

    /**
     * GET /api/namespaces/{namespaceId}/files/content?path=
     *
     * Returns the UTF-8 text content of a single file. The server rejects non-UTF-8 files with 400.
     */
    @Operation(
        summary = "Read namespace exchange file content",
        description = "GET /api/namespaces/{namespaceId}/files/content — read the UTF-8 text content of a namespace exchange file.",
    )
    fun getNamespaceFileContent(
        namespaceId: UUID,
        path: String,
    ): ExchangeFileContent

    /**
     * DELETE /api/namespaces/{namespaceId}/files?path=
     *
     * Deletes a single file from the namespace-shared exchange scope. Requires Namespace `WRITE`.
     */
    @Operation(
        summary = "Delete namespace exchange file",
        description = "DELETE /api/namespaces/{namespaceId}/files — delete a file from the namespace-shared exchange scope.",
    )
    fun deleteNamespaceFile(
        namespaceId: UUID,
        path: String,
    ): ExchangeDeleteResponse
}
