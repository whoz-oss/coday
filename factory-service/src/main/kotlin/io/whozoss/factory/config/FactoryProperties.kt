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
    )
}
