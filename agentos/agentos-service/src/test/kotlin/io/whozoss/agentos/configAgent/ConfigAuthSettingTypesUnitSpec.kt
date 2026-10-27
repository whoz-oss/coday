package io.whozoss.agentos.configAgent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.authSetting.AuthType

class ConfigAuthSettingTypesUnitSpec :
    StringSpec({
        "every auth type is described with its own fields, secrets marked" {
            val described = ConfigAuthSettingTypes.description.split("; ").map { it.substringBefore(" (") }

            described shouldContainExactlyInAnyOrder AuthType.entries.map { it.name }
            ConfigAuthSettingTypes.description shouldContain "OAUTH_REGISTERED (clientId, clientSecret*, authorizationUrl, tokenUrl, scopes)"
            ConfigAuthSettingTypes.description shouldContain "BASIC_AUTH (username, password*)"
            ConfigAuthSettingTypes.description shouldNotContain "namespaceId"
        }
    })
