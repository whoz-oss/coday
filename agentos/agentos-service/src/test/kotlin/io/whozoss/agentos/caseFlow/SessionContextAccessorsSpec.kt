package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Tests for the tolerant reads of [SessionContextKeys] values: a value of an unexpected type reads as absent. */
class SessionContextAccessorsSpec :
    StringSpec({

        // -------------------------------------------------------------------------
        // preferredLanguage
        // -------------------------------------------------------------------------

        "preferredLanguage is the string under its key" {
            mapOf<String, Any?>(SessionContextKeys.PREFERRED_LANGUAGE to "fr").preferredLanguage() shouldBe "fr"
        }

        "preferredLanguage is null when absent, blank, null or not a string" {
            emptyMap<String, Any?>().preferredLanguage() shouldBe null
            mapOf<String, Any?>(SessionContextKeys.PREFERRED_LANGUAGE to "  ").preferredLanguage() shouldBe null
            mapOf<String, Any?>(SessionContextKeys.PREFERRED_LANGUAGE to null).preferredLanguage() shouldBe null
            mapOf<String, Any?>(SessionContextKeys.PREFERRED_LANGUAGE to 42).preferredLanguage() shouldBe null
        }

        // -------------------------------------------------------------------------
        // userDisplayName
        // -------------------------------------------------------------------------

        fun context(userContext: Any?) = mapOf<String, Any?>(SessionContextKeys.USER_CONTEXT to userContext)

        "userDisplayName joins the first and last names" {
            context(mapOf("firstName" to "Alice", "lastName" to "Smith")).userDisplayName() shouldBe "Alice Smith"
        }

        "userDisplayName works with a single name" {
            context(mapOf("firstName" to "Alice", "lastName" to null)).userDisplayName() shouldBe "Alice"
            context(mapOf("lastName" to "Smith")).userDisplayName() shouldBe "Smith"
        }

        "a blank name is ignored, and no usable name at all is null" {
            context(mapOf("firstName" to "  ", "lastName" to "Smith")).userDisplayName() shouldBe "Smith"
            context(mapOf("firstName" to "", "lastName" to " ")).userDisplayName() shouldBe null
        }

        "a name that is not a string is ignored instead of failing" {
            // the former unchecked cast to Map<String, String> would have thrown here
            context(mapOf("firstName" to 42, "lastName" to "Smith")).userDisplayName() shouldBe "Smith"
            context(mapOf("firstName" to listOf("Alice"), "lastName" to 1)).userDisplayName() shouldBe null
        }

        "userDisplayName is null without a userContext, or with one that is not an object" {
            emptyMap<String, Any?>().userDisplayName() shouldBe null
            context(null).userDisplayName() shouldBe null
            context("Alice Smith").userDisplayName() shouldBe null
            context(listOf("Alice")).userDisplayName() shouldBe null
        }
    })
