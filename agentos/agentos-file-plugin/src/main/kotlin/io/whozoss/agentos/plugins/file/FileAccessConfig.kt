package io.whozoss.agentos.plugins.file

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * Typed configuration for the FILE_ACCESS integration.
 *
 * Deserialized from the persisted IntegrationConfig parameters.
 * Default values match the current hardcoded behaviour so existing
 * configs without the new fields keep working (backward-compatible).
 *
 * @property rootPath      Absolute path to the root directory to expose (required).
 * @property readOnly      If true, only read tools are provided (list/read/search).
 * @property readMaxSizeMb Maximum file size in megabytes for ReadFileTool. Default 10.
 *                         Clamped to [1, 50].
 * @property extraDenyPatterns Additional glob patterns to deny on top of the
 *           built-in [SensitiveFilePatterns.DEFAULT_PATTERNS]. Additive-only by design.
 *           null JSON values are coerced to emptyList() via @JsonSetter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class FileAccessConfig(
    val rootPath: String,
    val readOnly: Boolean = false,
    val readMaxSizeMb: Long = 10,
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    val extraDenyPatterns: List<String> = emptyList(),
) {
    /**
     * Effective deny patterns: built-in defaults merged with user-supplied extras.
     * Used by BoundaryPathResolver and SearchFilesTool.
     */
    val effectiveDenyPatterns: List<String>
        get() = SensitiveFilePatterns.DEFAULT_PATTERNS + extraDenyPatterns

    /**
     * Maximum read size in bytes, derived from [readMaxSizeMb].
     * Clamped to [1, 50] MB. 50 MB is already massive for LLM context windows.
     */
    val readMaxSizeBytes: Long
        get() = readMaxSizeMb.coerceIn(1, 50) * 1024 * 1024
}
