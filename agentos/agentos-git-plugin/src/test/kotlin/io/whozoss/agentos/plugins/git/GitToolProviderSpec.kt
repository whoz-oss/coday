package io.whozoss.agentos.plugins.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class GitToolProviderSpec :
    StringSpec({
        val provider = GitToolProvider()

        "declares the GIT integration with a configuration form" {
            provider.integrationType shouldBe "GIT"
            provider.configSchema.path("type").asText() shouldBe "object"
        }

        "provides no tool outside a case Git workspace" {
            provider.provideTools(null, "GIT").shouldBeEmpty()
            provider.provideTools(jacksonObjectMapper().createObjectNode(), "GIT").shouldBeEmpty()
        }
    })
