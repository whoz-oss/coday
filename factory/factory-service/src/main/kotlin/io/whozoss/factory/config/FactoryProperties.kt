package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.*` configuration tree.
 *
 * Mirrors the environment variables the Node Factory reads:
 *   - `FACTORY_BIND_HOST` / `FACTORY_UNSAFE_ALLOW_REMOTE_BIND`
 *   - `FACTORY_ORGANIZATION_ID` / `FACTORY_WORKSTREAM_ID`
 *   - `FACTORY_ALLOW_LOOPBACK_DEV` / `FACTORY_FAKE_IDP_SECRET`
 */
@ConfigurationProperties(prefix = "factory")
data class FactoryProperties(
    val bind: Bind = Bind(),
    val tenant: Tenant = Tenant(),
    val security: Security = Security(),
) {
    data class Bind(
        val host: String = "127.0.0.1",
        val unsafeAllowRemoteBind: Boolean = false,
    )

    data class Tenant(
        val organizationId: String = "default",
        val workstreamId: String = "default",
    )

    data class Security(
        // Fail-closed by default in a real deployment; the local application.yml
        // opts in to loopback-dev for developer convenience.
        val allowLoopbackDev: Boolean = false,
        val fakeIdpSecret: String = "coday-fake-idp-dev-secret",
        val httpApiSubmission: HttpApiSubmission = HttpApiSubmission(),
        val workstreamService: WorkstreamServiceAccess = WorkstreamServiceAccess(),
    )

    /**
     * Opt-in authentication and authorization boundary for the generic
     * AgentOS HTTP_API step-result callback.
     *
     * The bearer is a deployment-level service credential, never an attempt
     * capability and never prompt data. Authorization is granted only by a
     * durable binding created for each attempt explicitly entrusted to this
     * verified service identity.
     */
    data class HttpApiSubmission(
        val enabled: Boolean = false,
        val bearerToken: String = "",
        val serviceIdentityId: String = "agentos-http-api",
    )

    /**
     * Opt-in service identity of the conversational `FACTORY_WORKSTREAM` HTTP_API
     * integration (read, start a workflow, request a retry).
     *
     * Distinct from [HttpApiSubmission]: its own bearer, its own service identity
     * and a server-configured tenant boundary. The tenant
     * ([organizationId]/[workstreamId]) and the [permissions] are administrative
     * configuration, never request data. Every field fails closed: an enabled
     * configuration with a missing or invalid value refuses to start.
     *
     * Permissions: `read`, `start_workflow`, `request_retry`. All agents the
     * integration is attached to share these service rights: the credential does
     * not carry the identity or the rights of the person talking to the agent.
     */
    data class WorkstreamServiceAccess(
        val enabled: Boolean = false,
        val bearerToken: String = "",
        val serviceIdentityId: String = "agentos-workstream",
        val organizationId: String = "",
        val workstreamId: String = "",
        val permissions: Set<String> = emptySet(),
    )
}
