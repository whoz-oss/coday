package io.whozoss.agentos.plugins.http

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class HttpApiPluginHolderUnitSpec : StringSpec({
    "unsecure HTTP is allowed by default and for an explicit false" {
        listOf(null, "", "false", "FALSE").forEach { value ->
            HttpApiPluginHolder.allowUnsecure(value) shouldBe true
        }
    }

    "unsecure HTTP is disallowed by an explicit true ignoring case" {
        listOf("true", "TRUE", "True").forEach { value ->
            HttpApiPluginHolder.allowUnsecure(value) shouldBe false
        }
    }

    "unsecure HTTP is disallowed for an unrecognised value (fail closed)" {
        listOf("1", "yes", " true ").forEach { value ->
            HttpApiPluginHolder.allowUnsecure(value) shouldBe false
        }
    }
})
