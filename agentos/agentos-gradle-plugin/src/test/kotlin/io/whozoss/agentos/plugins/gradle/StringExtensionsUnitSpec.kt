package io.whozoss.agentos.plugins.gradle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

class StringExtensionsUnitSpec :
    StringSpec({

        "filterJvmNoise removes the four JVM restricted-method lines" {
            val raw =
                """
                WARNING: A restricted method in java.lang.System has been called
                WARNING: java.lang.System::load has been called by net.rubygrapefruit.platform.NativeLibraryLoader in an unnamed module (file:/x.jar)
                WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
                WARNING: Restricted methods will be blocked in a future release unless native access is enabled
                BUILD FAILED
                """.trimIndent()

            raw.filterJvmNoise() shouldBe "BUILD FAILED"
        }

        "filterJvmNoise keeps the other WARNING lines, which are real Gradle warnings" {
            val raw =
                """
                WARNING: Support for Kotlin 1.9 is deprecated
                WARNING: The following dependencies could not be resolved
                """.trimIndent()

            raw.filterJvmNoise() shouldBe raw
        }

        "filterJvmNoise collapses the blank lines left by the removed block" {
            val raw = "first\n\nWARNING: A restricted method in java.lang.System has been called\n\n\nsecond"

            raw.filterJvmNoise() shouldBe "first\n\nsecond"
        }

        "filterJvmNoise leaves an output without noise untouched" {
            "> Task :cli:test\nBUILD SUCCESSFUL".filterJvmNoise() shouldBe "> Task :cli:test\nBUILD SUCCESSFUL"
        }

        "keepEnd leaves an output within the bound untouched" {
            "abc".keepEnd(max = 3) shouldBe "abc"
        }

        "keepEnd keeps the last chars of a longer output and says how many came before" {
            val bounded = ("START" + "x".repeat(100) + "THE-END").keepEnd(max = 20)

            bounded shouldEndWith "x".repeat(13) + "THE-END"
            bounded shouldContain "92 earlier chars omitted"
            bounded shouldNotContain "START"
        }
    })
