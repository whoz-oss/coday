package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HttpApiPluginHolderUnitSpec : StringSpec({
    "unsecure HTTP is disabled for absent false and unknown values" {
        listOf(null, "", "false", "1", "yes", " true ").forEach { value ->
            HttpApiPluginHolder.allowUnsecure(value) shouldBe false
        }
    }

    "unsecure HTTP is enabled only by explicit true ignoring case" {
        listOf("true", "TRUE", "True").forEach { value ->
            HttpApiPluginHolder.allowUnsecure(value) shouldBe true
        }
    }
})
