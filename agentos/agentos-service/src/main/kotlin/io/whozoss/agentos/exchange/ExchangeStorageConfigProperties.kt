package io.whozoss.agentos.exchange

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the AgentOS file exchange.
 *
 * Bound from the `agentos.exchange` prefix in application.yml.
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   exchange:
 *     mount-root: data/exchange/
 * ```
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_EXCHANGE_MOUNT_ROOT
 * - AGENTOS_EXCHANGE_ALLOWED_UPLOAD_EXTENSIONS (comma-separated)
 */
@ConfigurationProperties(prefix = "agentos.exchange")
data class ExchangeStorageConfigProperties(
    /**
     * Root directory under which all exchange files are stored.
     * Relative paths are resolved against the JVM working directory.
     *
     * Per-scope layout below this root:
     * - `<mountRoot>/<namespaceId>/cases/<YYYY>/<MM>/<DD>/<caseId>` — case-scoped files (date-sharded)
     * - `<mountRoot>/<namespaceId>/shared`         — namespace-shared files
     */
    val mountRoot: String = "data/exchange/",
    /**
     * Allowed file extensions for user uploads (lowercase, no leading dot). An empty set allows any
     * extension. Overridable via `AGENTOS_EXCHANGE_ALLOWED_UPLOAD_EXTENSIONS` (comma-separated).
     */
    val allowedUploadExtensions: Set<String> =
        setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "yaml", "yml", "xml", "html", "htm", "log",
            "pdf", "png", "jpg", "jpeg", "gif", "svg", "webp",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods",
            "js", "ts", "py", "java", "kt", "kts", "sh", "sql", "css", "scss", "ini", "toml", "properties",
        ),
    /**
     * Maximum size (in bytes) of a file the read/download endpoints will load into memory. Files above
     * this are rejected rather than risking an OutOfMemoryError. Uploads are already capped by Spring's
     * multipart limit, but agent-produced files (written via the file-plugin) are not, so this guards the
     * read path. Overridable via `AGENTOS_EXCHANGE_READ_MAX_SIZE_BYTES`. Default 100 MB.
     */
    val readMaxSizeBytes: Long = 100L * 1024 * 1024,
)
