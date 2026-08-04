package io.whozoss.agentos.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StringExtensionsTest : StringSpec({

    "toSlug converts space-separated words to hyphenated slug" {
        "Daily Digest".toSlug() shouldBe "daily-digest"
    }

    "toSlug handles diacritics" {
        "café".toSlug() shouldBe "cafe"
    }

    "toSlug handles mixed diacritics and digits" {
        "Rapport hébd0".toSlug() shouldBe "rapport-hebd0"
    }

    "toSlug strips special characters" {
        "My Agent!".toSlug() shouldBe "my-agent"
    }

    "toSlug trims leading and trailing spaces" {
        "  hello  world  ".toSlug() shouldBe "hello-world"
    }

    "toSlug leaves an already-valid slug unchanged" {
        "daily-digest".toSlug() shouldBe "daily-digest"
    }

    "toSlug collapses consecutive non-alphanumeric chars to a single hyphen" {
        "foo---bar".toSlug() shouldBe "foo-bar"
    }

    "toSlug lowercases uppercase input" {
        "HELLO WORLD".toSlug() shouldBe "hello-world"
    }

    "toSlug handles full French word with diacritics" {
        "Réunion hebdo".toSlug() shouldBe "reunion-hebdo"
    }
})
