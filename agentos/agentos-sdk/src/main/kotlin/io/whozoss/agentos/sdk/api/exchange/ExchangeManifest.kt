package io.whozoss.agentos.sdk.api.exchange

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Listing of files in an exchange scope together with the caller's capability over it.
 *
 * Returned by `GET /api/cases/{caseId}/files/manifest` and
 * `GET /api/namespaces/{namespaceId}/files/manifest`.
 */
@Schema(name = "ExchangeManifest", description = "Files in an exchange scope plus the caller's capability.")
@JsonIgnoreProperties(ignoreUnknown = true)
data class ExchangeManifest(
    @field:Schema(description = "Files available in the scope.")
    val files: List<ExchangeFileEntry>,
    @field:Schema(description = "Capability the caller has over this scope.")
    val capability: ExchangeCapability,
)
