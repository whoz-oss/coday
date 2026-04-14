package io.whozoss.agentos.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for identity resolution mode.
 *
 * Bound from the `agentos.security` prefix in application.yml.
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   security:
 *     mode: local   # or: auth
 * ```
 *
 * Override with environment variable (Spring Boot relaxed binding):
 * - AGENTOS_SECURITY_MODE
 */
@ConfigurationProperties(prefix = "agentos.security")
data class SecurityConfigProperties(
    /**
     * Identity resolution mode.
     * [SecurityMode.LOCAL] is the default — suitable for single-user local development.
     * [SecurityMode.AUTH] requires an upstream proxy (Cloudflare Access or Express) that
     * populates the CF_Authorization or x-forwarded-email header on every request.
     */
    val mode: SecurityMode = SecurityMode.LOCAL,
    /**
     * Permissive mode (FR30).
     * When `true`, users without an assigned role are granted access by default.
     * This ensures backward compatibility for namespaces without configured permissions.
     * Default: `true`.
     */
    val permissive: Boolean = true,
)

enum class SecurityMode {
    LOCAL,
    AUTH,
}
