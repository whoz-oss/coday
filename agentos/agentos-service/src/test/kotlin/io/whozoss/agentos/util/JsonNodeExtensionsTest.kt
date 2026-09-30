package io.whozoss.agentos.util

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JsonNodeExtensionsTest : StringSpec({

    val node = ObjectMapper().readTree("""{"text": "  main  ", "blank": "   ", "empty": "", "nothing": null}""")

    "trimmedTextOrNull returns the trimmed text" {
        node.trimmedTextOrNull("text") shouldBe "main"
    }

    "trimmedTextOrNull returns null for a missing field" {
        node.trimmedTextOrNull("missing") shouldBe null
    }

    "trimmedTextOrNull returns null for a null field" {
        node.trimmedTextOrNull("nothing") shouldBe null
    }

    "trimmedTextOrNull returns null for an empty or blank field" {
        node.trimmedTextOrNull("empty") shouldBe null
        node.trimmedTextOrNull("blank") shouldBe null
    }
})
