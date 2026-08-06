package io.whozoss.agentos.authSetting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [maskSensitiveValue], [maskDataMap], and [isDataValueMasked].
 */
class AuthSettingDataMaskingSpec :
    StringSpec({
        timeout = 5000

        // -------------------------------------------------------------------------
        // maskSensitiveValue
        // -------------------------------------------------------------------------

        "maskSensitiveValue returns **** for null input" {
            maskSensitiveValue(null) shouldBe "****"
        }

        "maskSensitiveValue returns **** for blank input" {
            maskSensitiveValue("   ") shouldBe "****"
        }

        "maskSensitiveValue returns **** for key of exactly 8 chars" {
            maskSensitiveValue("12345678") shouldBe "****"
        }

        "maskSensitiveValue returns **** for key shorter than 8 chars" {
            maskSensitiveValue("abc") shouldBe "****"
        }

        "maskSensitiveValue shows first 2 and last 2 for key of 9 chars" {
            maskSensitiveValue("123456789") shouldBe "12****89"
        }

        "maskSensitiveValue shows first 2 and last 2 for key of 11 chars" {
            maskSensitiveValue("12345678901") shouldBe "12****01"
        }

        "maskSensitiveValue shows first 4 and last 4 for key of exactly 12 chars" {
            maskSensitiveValue("123456789012") shouldBe "1234****9012"
        }

        "maskSensitiveValue shows first 4 and last 4 for a long key" {
            maskSensitiveValue("sk-ant-api03-abcdefghijklmnop") shouldBe "sk-a****mnop"
        }

        // -------------------------------------------------------------------------
        // maskDataMap
        // -------------------------------------------------------------------------

        "maskDataMap returns null for null input" {
            maskDataMap(null) shouldBe null
        }

        "maskDataMap returns null for empty map" {
            maskDataMap(emptyMap()) shouldBe null
        }

        "maskDataMap masks all values in the map" {
            val data = mapOf(
                "short" to "abc",
                "medium" to "123456789",
                "long" to "sk-ant-api03-abcdefghijklmnop",
            )
            val masked = maskDataMap(data)
            masked shouldBe mapOf(
                "short" to "****",
                "medium" to "12****89",
                "long" to "sk-a****mnop",
            )
        }

        "maskDataMap preserves all keys, masking each value independently" {
            val data = mapOf("clientId" to "my-client", "clientSecret" to "super-secret-value-here")
            val masked = maskDataMap(data)!!
            masked.keys shouldBe setOf("clientId", "clientSecret")
            masked["clientId"] shouldBe "my****nt"  // 9 chars -> first 2 + **** + last 2
            masked["clientSecret"]!!.contains("****") shouldBe true
        }

        // -------------------------------------------------------------------------
        // isDataValueMasked
        // -------------------------------------------------------------------------

        "isDataValueMasked returns false for null" {
            isDataValueMasked(null) shouldBe false
        }

        "isDataValueMasked returns false for a plain value" {
            isDataValueMasked("my-client-secret") shouldBe false
        }

        "isDataValueMasked returns true for the fully masked sentinel" {
            isDataValueMasked("****") shouldBe true
        }

        "isDataValueMasked returns true for a partially masked value" {
            isDataValueMasked("sk-a****mnop") shouldBe true
        }

        "isDataValueMasked returns true for medium-masked value" {
            isDataValueMasked("12****89") shouldBe true
        }
    })
