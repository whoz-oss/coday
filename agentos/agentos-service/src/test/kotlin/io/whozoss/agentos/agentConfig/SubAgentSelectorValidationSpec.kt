package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * What [SubAgentSelectorValidation] has to get right: an entity id is refused with the correction in
 * the message, and every shape a name or a glob can take still goes through.
 */
class SubAgentSelectorValidationSpec : StringSpec({

    "an entity id is refused as 400" {
        val id = UUID.randomUUID().toString()

        val thrown =
            shouldThrow<ResponseStatusException> {
                SubAgentSelectorValidation.requireNameSelectors(listOf(id))
            }

        thrown.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    "the refusal names the offending id and the field it belongs to" {
        val id = UUID.randomUUID().toString()

        val thrown =
            shouldThrow<ResponseStatusException> {
                SubAgentSelectorValidation.requireNameSelectors(listOf("Reviewer", id))
            }

        // The caller has to see which entry to replace, and by what.
        thrown.reason!! shouldContain id
        thrown.reason!! shouldContain "name"
    }

    "an uppercase id is refused too" {
        val id = UUID.randomUUID().toString().uppercase()

        shouldThrow<ResponseStatusException> {
            SubAgentSelectorValidation.requireNameSelectors(listOf(id))
        }
    }

    "a padded id is refused: the surrounding blanks are not what makes it a name" {
        val id = UUID.randomUUID().toString()

        shouldThrow<ResponseStatusException> {
            SubAgentSelectorValidation.requireNameSelectors(listOf("  $id  "))
        }
    }

    "the count covers every offending entry, not only the ones quoted" {
        val ids = (1..12).map { UUID.randomUUID().toString() }

        val thrown =
            shouldThrow<ResponseStatusException> {
                SubAgentSelectorValidation.requireNameSelectors(ids)
            }

        thrown.reason!! shouldContain "12 entity id(s)"
    }

    "plain names pass" {
        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNameSelectors(listOf("Reviewer", "BugFixer"))
        }
    }

    "glob patterns pass" {
        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNameSelectors(listOf("*", "*Fixer", "Fixer*"))
        }
    }

    "null and empty pass: they mean no delegation, which is a valid configuration" {
        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNameSelectors(null)
            SubAgentSelectorValidation.requireNameSelectors(emptyList())
        }
    }

    "a name that merely contains an id is not an id" {
        val id = UUID.randomUUID().toString()

        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNameSelectors(listOf("agent-$id"))
        }
    }

    "an id-shaped glob stays a glob" {
        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNameSelectors(listOf("3f2504e0-*"))
        }
    }
})
