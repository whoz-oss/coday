package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GitMetadataEntriesSpec :
    StringSpec({
        "Git reserves its metadata entry in every Exchange" {
            GitMetadataEntries().names() shouldBe setOf(".git")
        }
    })
