package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.BadRequestException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

/**
 * What [SubAgentSelectorValidation] has to get right: an entity id is refused with the correction in
 * the message, and every shape a name or a glob can take still goes through.
 */
class SubAgentSelectorValidationSpec : StringSpec({

    "an entity id is refused as 400" {
        val id = UUID.randomUUID().toString()

        shouldThrow<BadRequestException> {
            SubAgentSelectorValidation.requireNameSelectors(listOf(id))
        }

        // The status rides on the exception type, which is what the rest of the controller throws.
        BadRequestException::class.java.getAnnotation(ResponseStatus::class.java).value shouldBe HttpStatus.BAD_REQUEST
    }

    "the refusal names the offending id and the field it belongs to" {
        val id = UUID.randomUUID().toString()

        val thrown =
            shouldThrow<BadRequestException> {
                SubAgentSelectorValidation.requireNameSelectors(listOf("Reviewer", id))
            }

        // The caller has to see which entry to replace, and by what.
        thrown.message!! shouldContain id
        thrown.message!! shouldContain "name"
    }

    "an uppercase id is refused too" {
        val id = UUID.randomUUID().toString().uppercase()

        shouldThrow<BadRequestException> {
            SubAgentSelectorValidation.requireNameSelectors(listOf(id))
        }
    }

    "a padded id is refused: the surrounding blanks are not what makes it a name" {
        val id = UUID.randomUUID().toString()

        shouldThrow<BadRequestException> {
            SubAgentSelectorValidation.requireNameSelectors(listOf("  $id  "))
        }
    }

    "the count covers every offending entry, not only the ones quoted" {
        val ids = (1..12).map { UUID.randomUUID().toString() }

        val thrown =
            shouldThrow<BadRequestException> {
                SubAgentSelectorValidation.requireNameSelectors(ids)
            }

        thrown.message!! shouldContain "12 entity id(s)"
    }

    "an id already stored is carried over, not refused" {
        // A PUT echoes the whole resource back, so refusing what the caller merely re-sends would
        // make an agent that already holds an id impossible to edit at all.
        val id = UUID.randomUUID().toString()

        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNoNewIdSelectors(listOf(id, "Reviewer"), listOf(id))
        }
    }

    "an id this write introduces is refused even when others were stored" {
        val stored = UUID.randomUUID().toString()
        val fresh = UUID.randomUUID().toString()

        val thrown =
            shouldThrow<BadRequestException> {
                SubAgentSelectorValidation.requireNoNewIdSelectors(listOf(stored, fresh), listOf(stored))
            }

        thrown.message!! shouldContain fresh
        // Only the new one is named: the stored one is not this write's fault.
        thrown.message!!.contains(stored) shouldBe false
    }

    "carrying over ignores case and padding, like the stored value's own spelling" {
        val id = UUID.randomUUID().toString()

        shouldNotThrowAny {
            SubAgentSelectorValidation.requireNoNewIdSelectors(listOf("  ${id.uppercase()}  "), listOf(id))
        }
    }

    "an id that is not the stored one is this write's, however it is spelled" {
        // The other half of the normalisation: trimming and lowercasing both sides must not make
        // any id look carried over. Without this, comparing loosely would let a new id through.
        val stored = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()

        val thrown =
            shouldThrow<BadRequestException> {
                SubAgentSelectorValidation.requireNoNewIdSelectors(listOf("  ${other.uppercase()}  "), listOf(stored))
            }

        thrown.message!! shouldContain other.uppercase()
    }

    "with nothing stored, every id is one this write introduces" {
        val id = UUID.randomUUID().toString()

        shouldThrow<BadRequestException> {
            SubAgentSelectorValidation.requireNoNewIdSelectors(listOf(id), null)
        }
        shouldThrow<BadRequestException> {
            SubAgentSelectorValidation.requireNoNewIdSelectors(listOf(id), emptyList())
        }
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
