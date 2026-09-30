package io.whozoss.agentos.git.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GitCommandResultSpec :
    StringSpec({
        "diagnostics keep stderr then stdout, each capped" {
            val result = GitCommandResult.Completed(1, stdout = " out\n" + "o".repeat(3_000), stderr = "err\n")

            result.diagnostics shouldBe "err\nout\n" + "o".repeat(1_996)
        }

        "diagnostics skip an empty stream" {
            GitCommandResult.Completed(1, stdout = "", stderr = " only stderr ").diagnostics shouldBe "only stderr"
            GitCommandResult.Completed(1, stdout = "only stdout", stderr = "").diagnostics shouldBe "only stdout"
            GitCommandResult.Completed(1, stdout = "", stderr = "").diagnostics shouldBe ""
        }
    })
