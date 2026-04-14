package io.whozoss.agentos.aiProvider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [maskApiKey] and [isMasked].
 */
class ApiKeyMaskingSpec :
    StringSpec({
        timeout = 5000

        // -------------------------------------------------------------------------
        // maskApiKey
        // -------------------------------------------------------------------------

        "maskApiKey returns null for null input" {
            maskApiKey(null) shouldBe null
        }

        "maskApiKey returns null for blank input" {
            maskApiKey("   ") shouldBe null
        }

        "maskApiKey returns **** for key of exactly 8 chars" {
            maskApiKey("12345678") shouldBe "****"
        }

        "maskApiKey returns **** for key shorter than 8 chars" {
            maskApiKey("abc") shouldBe "****"
        }

        "maskApiKey shows first 2 and last 2 for key of 9 chars" {
            maskApiKey("123456789") shouldBe "12****89"
        }

        "maskApiKey shows first 2 and last 2 for key of 11 chars" {
            maskApiKey("12345678901") shouldBe "12****01"
        }

        "maskApiKey shows first 4 and last 4 for key of exactly 12 chars" {
            maskApiKey("123456789012") shouldBe "1234****9012"
        }

        "maskApiKey shows first 4 and last 4 for long key" {
            maskApiKey("sk-ant-api03-abcdefghijklmnop") shouldBe "sk-a****mnop"
        }

        // -------------------------------------------------------------------------
        // isMasked
        // -------------------------------------------------------------------------

        "isMasked returns false for null" {
            isMasked(null) shouldBe false
        }

        "isMasked returns false for plain key" {
            isMasked("sk-ant-api03-abcdef") shouldBe false
        }

        "isMasked returns true for fully masked value" {
            isMasked("****") shouldBe true
        }

        "isMasked returns true for partially masked value" {
            isMasked("sk-a****mnop") shouldBe true
        }
    })
