package io.whozoss.agentos.git

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GitRefNamesSpec :
    StringSpec({

        "branch names git rejects are rejected here too" {
            listOf(
                "",
                "@",
                "-dash-leading",
                "/leading",
                "trailing/",
                "double//slash",
                "dot..dot",
                "at@{brace",
                "trailing.",
                ".hidden/component",
                "component.lock",
                "with space",
                "with~tilde",
                "with^caret",
                "with:colon",
                "with?question",
                "with*star",
                "with[bracket",
            ).forEach { name ->
                withClue(name) { GitRefNames.isValidBranchName(name) shouldBe false }
            }
        }

        "ordinary branch names are accepted" {
            listOf("main", "feature/login", "release/2.0", "fix-1234", "user/selim/topic").forEach { name ->
                withClue(name) { GitRefNames.isValidBranchName(name) shouldBe true }
            }
        }
    })
