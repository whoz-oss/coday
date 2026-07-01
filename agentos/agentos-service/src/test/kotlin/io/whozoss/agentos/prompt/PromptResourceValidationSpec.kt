package io.whozoss.agentos.prompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import jakarta.validation.Validation
import jakarta.validation.Validator

/**
 * Unit tests for Bean Validation constraints on [PromptResource] and [PromptParameterResource].
 *
 * Uses the default Jakarta Validator directly — no Spring context needed.
 * Covers the DTO-level constraints (@NotBlank, @NotEmpty) that are applied
 * by Spring MVC before the controller body is invoked.
 */
class PromptResourceValidationSpec : StringSpec() {
    private val validator: Validator =
        Validation.buildDefaultValidatorFactory().validator

    private fun validResource(
        name: String = "My Prompt",
        content: List<String> = listOf("Hello"),
        parameters: List<PromptParameterResource> = emptyList(),
    ) = PromptResource(
        name = name,
        content = content,
        parameters = parameters,
    )

    private fun validParameter(
        name: String = "lang",
        defaultValue: String = "English",
    ) = PromptParameterResource(name = name, defaultValue = defaultValue)

    init {
        // -------------------------------------------------------------------------
        // PromptResource — name
        // -------------------------------------------------------------------------

        "PromptResource: blank name produces a violation" {
            val violations = validator.validate(validResource(name = "   "))
            violations shouldHaveSize 1
            violations.first().propertyPath.toString() shouldContain "name"
        }

        "PromptResource: empty name produces a violation" {
            val violations = validator.validate(validResource(name = ""))
            violations shouldHaveSize 1
        }

        "PromptResource: valid name produces no violation" {
            validator.validate(validResource(name = "My Prompt")).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // PromptResource — content
        // -------------------------------------------------------------------------

        "PromptResource: empty content list produces a violation" {
            val violations = validator.validate(validResource(content = emptyList()))
            violations shouldHaveSize 1
            violations.first().propertyPath.toString() shouldContain "content"
        }

        "PromptResource: non-empty content produces no violation" {
            validator.validate(validResource(content = listOf("Hello"))).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // PromptParameterResource — name
        // -------------------------------------------------------------------------

        "PromptParameterResource: blank name produces a violation" {
            val violations = validator.validate(validParameter(name = "  "))
            violations shouldHaveSize 1
            violations.first().propertyPath.toString() shouldContain "name"
        }

        "PromptParameterResource: valid name produces no violation" {
            validator.validate(validParameter(name = "language")).shouldBeEmpty()
        }

        // -------------------------------------------------------------------------
        // PromptParameterResource — defaultValue
        // -------------------------------------------------------------------------

        "PromptParameterResource: empty defaultValue produces a violation" {
            val violations = validator.validate(validParameter(defaultValue = ""))
            violations shouldHaveSize 1
            violations.first().propertyPath.toString() shouldContain "defaultValue"
        }

        "PromptParameterResource: blank defaultValue produces a violation" {
            val violations = validator.validate(validParameter(defaultValue = "   "))
            violations shouldHaveSize 1
            violations.first().propertyPath.toString() shouldContain "defaultValue"
        }

        "PromptParameterResource: non-blank defaultValue produces no violation" {
            validator.validate(validParameter(defaultValue = "English")).shouldBeEmpty()
        }
    }
}
