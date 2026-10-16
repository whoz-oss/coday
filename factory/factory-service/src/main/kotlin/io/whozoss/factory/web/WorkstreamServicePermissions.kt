package io.whozoss.factory.web

/**
 * Permissions of the `FACTORY_WORKSTREAM` service identity and the narrow scopes
 * they map to in the verified [TrustContext].
 *
 * The permissions are configured server side
 * (`factory.security.workstream-service.permissions`); a scope is only ever
 * derived from that configuration, never from a header, a body or a model
 * argument.
 */
object WorkstreamServicePermissions {
    const val READ = "read"
    const val START_WORKFLOW = "start_workflow"
    const val REQUEST_RETRY = "request_retry"

    const val READ_SCOPE = "factory:workstream:read"
    const val START_SCOPE = "factory:workstream:start"
    const val RETRY_SCOPE = "factory:workstream:retry"

    val ALL: Set<String> = setOf(READ, START_WORKFLOW, REQUEST_RETRY)

    fun isKnown(permission: String): Boolean = permission in ALL

    /** The verified-context scope granted by [permission], or `null` for an unknown permission. */
    fun scopeFor(permission: String): String? = when (permission) {
        READ -> READ_SCOPE
        START_WORKFLOW -> START_SCOPE
        REQUEST_RETRY -> RETRY_SCOPE
        else -> null
    }
}
