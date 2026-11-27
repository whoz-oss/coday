package io.whozoss.agentos.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.authSetting.AuthType

class ConnectionPolicyUnitSpec : StringSpec({
    "strict mode denies a missing policy before connection use" {
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(mode = ConnectionPolicyMode.STRICT))
        shouldThrow<IllegalStateException> { registry.requireSetting(OAuthRegisteredAuthSetting(name = "unknown")) }
    }
    "administrator endpoint policy also binds an OAuth alias and denies shadow integration" {
        val policy = ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED),
            authorizationUrls = setOf("https://auth.example.test/authorize"), tokenUrls = setOf("https://auth.example.test/token"),
            allowedScopes = setOf("read"), allowedIntegrationTypes = setOf("GOOGLE_WORKSPACE"),
            resourceOrigins = setOf("https://api.example.test"))
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to policy)))
        val setting = OAuthRegisteredAuthSetting(name = "alias", authorizationUrl = "https://auth.example.test/authorize",
            tokenUrl = "https://auth.example.test/token", scopes = "read")
        shouldThrow<IllegalStateException> { registry.requireIntegration(setting, "HTTP_API") }
    }
    "a governed overlay cannot widen the lower layer requested scopes" {
        val base = OAuthRegisteredAuthSetting(name = "READ", authorizationUrl = "https://auth.example.test/auth", tokenUrl = "https://auth.example.test/token", scopes = "read")
        val strategy = io.whozoss.agentos.authSetting.AuthSettingMergeStrategy(ConnectionPolicyRegistry(ConnectionPolicyProperties(
            policies = mapOf("read" to ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED),
                authorizationUrls = setOf(base.authorizationUrl), tokenUrls = setOf(base.tokenUrl), allowedScopes = setOf("read", "write"))),
            authSettingNames = mapOf("READ" to setOf("read")))))
        shouldThrow<IllegalStateException> { strategy.merge(base, base.copy(scopes = "read write")) }
    }
    "unknown policy bindings fail closed at startup" {
        shouldThrow<IllegalStateException> { ConnectionPolicyRegistry(ConnectionPolicyProperties(authSettingNames = mapOf("READ" to setOf("unknown")))) }
    }
    "normalized HTTPS endpoints allow host case and default port but not query redirection" {
        val setting = OAuthRegisteredAuthSetting(name = "READ", authorizationUrl = "https://AUTH.example.test:443/auth", tokenUrl = "https://auth.example.test/token", scopes = "read")
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED), authorizationUrls = setOf("https://auth.example.test/auth"), tokenUrls = setOf(setting.tokenUrl), allowedScopes = setOf("read"), allowedIntegrationTypes = setOf("HTTP_API"), resourceOrigins = setOf("https://api.example.test"))), authSettingNames = mapOf("READ" to setOf("read"))))
        registry.requireResource(setting, "HTTP_API", "https://api.example.test/items?q=1")
        shouldThrow<IllegalStateException> { registry.requireSetting(setting.copy(tokenUrl = setting.tokenUrl + "?redirect=evil")) }
        shouldThrow<IllegalStateException> { registry.requireResource(setting, "HTTP_API", "https://attacker.example.test/items") }
        shouldThrow<IllegalStateException> { registry.requireSetting(setting.copy(scopes = "read write")) }
    }
    "governed overlays reject endpoint redirect and accept narrowing" {
        val base = OAuthRegisteredAuthSetting(name = "READ", authorizationUrl = "https://auth.example.test/auth", tokenUrl = "https://auth.example.test/token", scopes = "read metadata")
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = mapOf("read" to ConnectionPolicy(allowedAuthTypes = setOf(AuthType.OAUTH_REGISTERED), authorizationUrls = setOf(base.authorizationUrl), tokenUrls = setOf(base.tokenUrl), allowedScopes = setOf("read", "metadata")))))
        val strategy = io.whozoss.agentos.authSetting.AuthSettingMergeStrategy(registry)
        (strategy.merge(base, base.copy(scopes = "read")) as OAuthRegisteredAuthSetting).scopes shouldBe "read"
        shouldThrow<IllegalStateException> { strategy.merge(base, base.copy(tokenUrl = "https://attacker.example.test/token")) }
    }
    "platform policy snapshot cannot be widened through mutated config maps" {
        val names = mutableMapOf("READ" to setOf("unknown"))
        shouldThrow<IllegalStateException> { ConnectionPolicyRegistry(ConnectionPolicyProperties(authSettingNames = names)) }
        val policies = mutableMapOf("read" to ConnectionPolicy(allowedAuthTypes = setOf(AuthType.BEARER_TOKEN), allowedIntegrationTypes = setOf("HTTP_API")))
        names["READ"] = setOf("read")
        val registry = ConnectionPolicyRegistry(ConnectionPolicyProperties(policies = policies, authSettingNames = names))
        policies.clear(); names.clear()
        shouldThrow<IllegalStateException> { registry.requireIntegration(io.whozoss.agentos.authSetting.BearerTokenAuthSetting(name = "READ"), "BASH") }
    }
    "compatibility preserves unbound legacy settings" {
        ConnectionPolicyRegistry().requireSetting(OAuthRegisteredAuthSetting(name = "legacy"))
    }
})
