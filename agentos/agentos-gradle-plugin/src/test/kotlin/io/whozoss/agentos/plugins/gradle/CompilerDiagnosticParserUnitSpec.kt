package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.plugins.gradle.CompilerDiagnosticParser.Severity

class CompilerDiagnosticParserUnitSpec :
    StringSpec({

        val parser = CompilerDiagnosticParser()

        // ── parse — Kotlin diagnostics ─────────────────────────────────────────────────────────

        "parse recognises Kotlin error line" {
            val raw = "e: /home/dev/project/src/main/kotlin/Foo.kt:12:5: Unresolved reference: bar"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].severity shouldBe Severity.ERROR
            diagnostics[0].file shouldBe "/home/dev/project/src/main/kotlin/Foo.kt"
            diagnostics[0].line shouldBe 12
            diagnostics[0].column shouldBe 5
            diagnostics[0].message shouldBe "Unresolved reference: bar"
        }

        "parse recognises Kotlin warning line" {
            val raw = "w: /project/src/main/kotlin/Bar.kt:3:1: Variable 'x' is never used"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].severity shouldBe Severity.WARNING
        }

        "parse recognises Kotlin 2.x error line with file URI prefix and no colon after the column" {
            val raw = "e: file:///home/dev/project/src/main/kotlin/Foo.kt:12:5 Unresolved reference 'bar'."
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].severity shouldBe Severity.ERROR
            diagnostics[0].file shouldBe "/home/dev/project/src/main/kotlin/Foo.kt"
            diagnostics[0].line shouldBe 12
            diagnostics[0].column shouldBe 5
            diagnostics[0].message shouldBe "Unresolved reference 'bar'."
        }

        "parse recognises Kotlin 2.x warning line with file URI prefix" {
            val raw = "w: file:///project/src/main/kotlin/Bar.kt:3:1 Variable 'x' is never used"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].severity shouldBe Severity.WARNING
            diagnostics[0].file shouldBe "/project/src/main/kotlin/Bar.kt"
        }

        "parse keeps a Kotlin 1.x message that contains a colon" {
            val raw = "e: /project/Foo.kt:1:2: Expecting ':' here"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].message shouldBe "Expecting ':' here"
        }

        "parse handles .kts extension" {
            val raw = "e: /project/build.gradle.kts:7:3: Type mismatch"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].file shouldBe "/project/build.gradle.kts"
        }

        // ── parse — Java diagnostics ─────────────────────────────────────────────────────────

        "parse recognises Java error line" {
            val raw = "/project/src/main/java/Foo.java:20: error: cannot find symbol"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].severity shouldBe Severity.ERROR
            diagnostics[0].file shouldBe "/project/src/main/java/Foo.java"
            diagnostics[0].line shouldBe 20
            diagnostics[0].column shouldBe null
            diagnostics[0].message shouldBe "cannot find symbol"
        }

        "parse recognises Java warning line" {
            val raw = "/project/src/main/java/Bar.java:5: warning: unchecked cast"
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 1
            diagnostics[0].severity shouldBe Severity.WARNING
        }

        // ── parse — noise / mixed ──────────────────────────────────────────────────────────

        "parse ignores non-diagnostic lines" {
            val raw =
                """
                > Task :cli:testClasses
                BUILD SUCCESSFUL in 3s
                """.trimIndent()
            parser.parse(raw).shouldBeEmpty()
        }

        "parse extracts multiple diagnostics from mixed output" {
            val raw =
                """
                > Task :engine:compileKotlin FAILED
                e: /project/engine/src/main/kotlin/Engine.kt:10:3: Unresolved reference: foo
                w: /project/engine/src/main/kotlin/Engine.kt:15:7: Variable 'x' is never used
                e: /project/engine/src/main/kotlin/Other.kt:2:1: Expecting '}'
                BUILD FAILED in 5s
                """.trimIndent()
            val diagnostics = parser.parse(raw)

            diagnostics shouldHaveSize 3
            diagnostics.count { it.severity == Severity.ERROR } shouldBe 2
            diagnostics.count { it.severity == Severity.WARNING } shouldBe 1
        }

        // ── buildSummary ───────────────────────────────────────────────────────────────────────

        "buildSummary SUCCESS with no diagnostics" {
            val summary = parser.buildSummary(exitCode = 0, diagnostics = emptyList(), rawOutput = "")

            summary shouldContain "SUCCESS"
            summary shouldContain "Errors: 0"
            summary shouldContain "Warnings: 0"
            summary shouldNotContain "Diagnostics"
            summary shouldNotContain "Raw output"
        }

        "buildSummary FAILURE with no parseable diagnostics includes raw output" {
            val summary =
                parser.buildSummary(
                    exitCode = 1,
                    diagnostics = emptyList(),
                    rawOutput = "Some weird compiler crash",
                )

            summary shouldContain "FAILURE"
            summary shouldContain "Raw output"
            summary shouldContain "Some weird compiler crash"
        }

        "buildSummary lists diagnostics without filter" {
            val diagnostics =
                listOf(
                    CompilerDiagnosticParser.Diagnostic(
                        severity = Severity.ERROR,
                        file = "/project/Foo.kt",
                        line = 5,
                        column = 3,
                        message = "Unresolved reference: x",
                    ),
                )
            val summary = parser.buildSummary(exitCode = 1, diagnostics = diagnostics, rawOutput = "")

            summary shouldContain "Errors: 1"
            summary shouldContain "[ERROR]"
            summary shouldContain "/project/Foo.kt:5:3"
            summary shouldContain "Unresolved reference: x"
        }

        "buildSummary with fileFilter shows targeted file first then others separately" {
            val diagnostics =
                listOf(
                    CompilerDiagnosticParser.Diagnostic(
                        severity = Severity.ERROR,
                        file = "/project/cli/src/main/kotlin/Foo.kt",
                        line = 5,
                        column = 1,
                        message = "Error in Foo",
                    ),
                    CompilerDiagnosticParser.Diagnostic(
                        severity = Severity.WARNING,
                        file = "/project/cli/src/main/kotlin/Bar.kt",
                        line = 9,
                        column = 2,
                        message = "Warning in Bar",
                    ),
                )
            val summary =
                parser.buildSummary(
                    exitCode = 1,
                    diagnostics = diagnostics,
                    rawOutput = "",
                    fileFilter = "cli/src/main/kotlin/Foo.kt",
                )

            summary shouldContain "Error in Foo"
            summary shouldContain "Other files"
            summary shouldContain "Warning in Bar"
            // Foo section must appear before Others section
            val fooIdx = summary.indexOf("Error in Foo")
            val othersIdx = summary.indexOf("Other files")
            (fooIdx < othersIdx) shouldBe true
        }

        "buildSummary with fileFilter matching nothing lists all diagnostics normally" {
            val diagnostics =
                listOf(
                    CompilerDiagnosticParser.Diagnostic(
                        severity = Severity.ERROR,
                        file = "/project/Bar.kt",
                        line = 1,
                        column = 1,
                        message = "Some error",
                    ),
                )
            val summary =
                parser.buildSummary(
                    exitCode = 1,
                    diagnostics = diagnostics,
                    rawOutput = "",
                    fileFilter = "NonExistent.kt",
                )

            summary shouldContain "Some error"
            summary shouldNotContain "Other files"
        }

        "parse then buildSummary of 5000 errors keeps the exact counts and a bounded list" {
            val raw = (1..5000).joinToString("\n") { "e: file:///project/src/Foo.kt:$it:1 Unresolved reference 'x$it'." }

            val diagnostics = parser.parse(raw)
            val summary = parser.buildSummary(exitCode = 1, diagnostics = diagnostics, rawOutput = raw)

            diagnostics shouldHaveSize 5000
            summary shouldContain "Errors: 5000"
            summary shouldContain "Unresolved reference 'x1'."
            summary shouldNotContain "Unresolved reference 'x5000'."
            summary shouldContain "4900 more diagnostic(s) not shown"
            summary.lines().size shouldBeLessThan 120
        }

        "buildSummary shares the bound between the target file and the other files" {
            val onTarget = (1..60).map { CompilerDiagnosticParser.Diagnostic(Severity.ERROR, "/p/Target.kt", it, 1, "target $it") }
            val onOthers = (1..60).map { CompilerDiagnosticParser.Diagnostic(Severity.ERROR, "/p/Other.kt", it, 1, "other $it") }

            val summary =
                parser.buildSummary(exitCode = 1, diagnostics = onOthers + onTarget, rawOutput = "", fileFilter = "Target.kt")

            summary shouldContain "target 60"
            summary shouldContain "other 40"
            summary shouldNotContain "other 41"
            summary shouldContain "20 more diagnostic(s) not shown"
        }
    })
