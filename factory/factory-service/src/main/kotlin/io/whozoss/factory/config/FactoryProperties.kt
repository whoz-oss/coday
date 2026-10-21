package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.*` configuration tree.
 *
 * Mirrors the environment variables the Node Factory reads:
 *   - `FACTORY_BIND_HOST` / `FACTORY_UNSAFE_ALLOW_REMOTE_BIND`
 *   - `FACTORY_ORGANIZATION_ID` / `FACTORY_WORKSTREAM_ID`
 *   - `FACTORY_FAKE_IDP_SECRET` / `FACTORY_SECURITY_MODE`
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
        /** Secret the HS256 JWTs of the Factory users are signed with. */
        val fakeIdpSecret: String = "coday-fake-idp-dev-secret",
        /**
         * Identity resolution mode (`FACTORY_SECURITY_MODE`, the same variable as AgentOS).
         * [SecurityMode.LOCAL] (default): every caller is the OS user running Factory.
         * [SecurityMode.AUTH]: identity is read from a Factory JWT or the upstream proxy headers.
         * See `io.whozoss.factory.web.security`.
         */
        val mode: SecurityMode = SecurityMode.LOCAL,
    )
}
