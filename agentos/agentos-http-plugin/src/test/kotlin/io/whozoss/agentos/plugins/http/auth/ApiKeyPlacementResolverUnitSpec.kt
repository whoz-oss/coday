package io.whozoss.agentos.plugins.http.auth

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.openapi.ApiKeyScheme
import io.whozoss.agentos.plugins.http.testing.CapturedLogs

class ApiKeyPlacementResolverUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val scheme = ApiKeyScheme(placement = ApiKeyPlacement.HEADER, name = "X-Api-Key")

    "an untouched config auth takes the placement of the document scheme" {
        ApiKeyPlacementResolver.resolve(AuthConfig(), scheme, configName = "KEYED") shouldBe ResolvedAuth(
            auth = AuthConfig(apiKeyIn = ApiKeyPlacement.HEADER, apiKeyName = "X-Api-Key"),
            fromDocument = true,
        )
    }

    "an explicit config auth wins over the document scheme" {
        val explicit = AuthConfig(apiKeyIn = ApiKeyPlacement.QUERY, apiKeyName = "token")
        ApiKeyPlacementResolver.resolve(explicit, scheme, configName = "KEYED") shouldBe
            ResolvedAuth(auth = explicit, fromDocument = false)
    }

    "an untouched config auth keeps the defaults when the document declares no scheme" {
        ApiKeyPlacementResolver.resolve(AuthConfig(), null, configName = "KEYED") shouldBe
            ResolvedAuth(auth = AuthConfig(), fromDocument = false)
    }

    "logs the chosen placement at DEBUG only" {
        CapturedLogs.clear()
        ApiKeyPlacementResolver.resolve(AuthConfig(), scheme, configName = "KEYED")
        val line = CapturedLogs.lines.single { "API key placement" in it }
        line shouldContain "DEBUG"
        line shouldContain "KEYED"
        line shouldContain "document"
        line shouldContain "header 'X-Api-Key'"
        CapturedLogs.clear()
        ApiKeyPlacementResolver.resolve(AuthConfig(apiKeyIn = ApiKeyPlacement.BEARER), scheme, configName = "KEYED")
        CapturedLogs.lines.single { "API key placement" in it } shouldNotContain "document"
    }
})
