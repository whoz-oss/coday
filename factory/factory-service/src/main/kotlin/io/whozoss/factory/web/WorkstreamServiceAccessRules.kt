package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties

/**
 * Fail-closed validation rules of the `FACTORY_WORKSTREAM` service identity
 * (`factory.security.workstream-service.*`).
 *
 * Shared by the startup validator (an enabled but invalid configuration refuses
 * to start) and by the [TrustContextExtractor] (defence in depth: an invalid
 * configuration never authenticates a caller, even if validation was bypassed).
 */
object WorkstreamServiceAccessRules {
    const val MIN_BEARER_BYTES = 32

    private val SAFE_IDENTITY = Regex("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$")

    /** Human-readable configuration problems; empty when the configuration is usable. */
    fun problems(security: FactoryProperties.Security): List<String> {
        val access = security.workstreamService
        val worker = security.httpApiSubmission
        val problems = mutableListOf<String>()
        val token = access.bearerToken
        when {
            token.isBlank() -> problems += "bearer-token is required"
            token.trim() != token -> problems += "bearer-token must not be wrapped in whitespace"
            token.toByteArray(Charsets.UTF_8).size < MIN_BEARER_BYTES ->
                problems += "bearer-token must be at least $MIN_BEARER_BYTES bytes"
        }
        if (!SAFE_IDENTITY.matches(access.serviceIdentityId)) {
            problems += "service-identity-id must be a safe identifier"
        }
        if (access.organizationId.isBlank() || access.organizationId.trim() != access.organizationId) {
            problems += "organization-id is required"
        }
        if (access.workstreamId.isBlank() || access.workstreamId.trim() != access.workstreamId) {
            problems += "workstream-id is required"
        }
        if (access.permissions.isEmpty()) {
            problems += "permissions must name at least one of ${WorkstreamServicePermissions.ALL.sorted()}"
        }
        access.permissions.filterNot(WorkstreamServicePermissions::isKnown).forEach {
            problems += "unknown permission '$it'"
        }
        // The two service credentials must never be interchangeable.
        if (worker.bearerToken.isNotBlank() && worker.bearerToken == token) {
            problems += "bearer-token must differ from the worker submission bearer token"
        }
        if (worker.serviceIdentityId == access.serviceIdentityId) {
            problems += "service-identity-id must differ from the worker submission service identity"
        }
        return problems
    }

    /** `true` only when the identity is enabled AND its configuration is fully valid. */
    fun isUsable(security: FactoryProperties.Security): Boolean =
        security.workstreamService.enabled && problems(security).isEmpty()
}
