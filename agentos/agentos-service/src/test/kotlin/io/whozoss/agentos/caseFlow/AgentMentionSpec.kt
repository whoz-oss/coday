package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AgentMentionSpec :
    StringSpec({
        fun show(text: String): String = text.replace("\n", "\\n").replace("\u00A0", "<nbsp>")

        // input to the name extractName must return, or null when the text holds no leading mention.
        // Inputs must be unique: each one names its own test.
        val extractNameCases: List<Pair<String, String?>> =
            listOf(
                // plain form
                "@Astra hello" to "Astra",
                "@Astra" to "Astra",
                "  \n @Astra hi" to "Astra",
                "@123 hi" to "123",
                "@inspector\u00A0https://example.com" to "inspector",
                "@\u00c9ric hi" to "\u00c9ric",
                "@Casting-Manager_2 hi" to "Casting-Manager_2",
                "@Astra. hi" to "Astra",
                // chip form
                "[@Astra](#agent=a8b12cb8-7d3a-42fe-a964-55cbb8f6e7aa) hello" to "Astra",
                "[@Astra](#agent=a8b12cb8-7d3a-42fe-a964-55cbb8f6e7aa)" to "Astra",
                "   [@Astra](#agent=x) hi" to "Astra",
                "[@Casting-Manager_2](#agent=x) hi" to "Casting-Manager_2",
                "[@Astra] hello" to "Astra",
                "[@Astra]hello" to "Astra",
                "[@Astra]() hello" to "Astra",
                "[@Casting Manager]()" to "Casting Manager",
                "[@Casting Manager]() hello" to "Casting Manager",
                "[@Casting Manager](#agent=x) hi" to "Casting Manager",
                "[@v1.2 Reviewer](#agent=x) hi" to "v1.2 Reviewer",
                "[@\u00c9ric l'agent](#agent=x) hi" to "\u00c9ric l'agent",
                "[@Astra ](#agent=x) hi" to "Astra",
                "[@Astra](#agent=x) see [docs](http://example.com)" to "Astra",
                "[@Astra](#agent=x)\u00A0https://example.com" to "Astra",
                "[@Astra](#agent=\nfoo) hi" to "Astra",
                // the link target is not parsed: it ends at the first closing parenthesis
                "[@Astra](#a)b) rest" to "Astra",
                // not a mention
                "" to null,
                "@" to null,
                "@ something" to null,
                "@!Astra" to null,
                "@@Astra hi" to null,
                "hello @Astra" to null,
                "[Astra](#agent=x)" to null,
                "[@](#agent=x)" to null,
                "[@ Astra](#agent=x) hi" to null,
                "[@Astra\nManager](#agent=x) hi" to null,
                "[@Astra hello" to null,
                "[[@Astra](#x)] hi" to null,
                // malformed link target: rejected as a whole rather than half-accepted
                "[@Astra](#agent=x" to null,
                "[@Astra](#a)(#b) hi" to null,
            )

        extractNameCases.forEach { (text, expected) ->
            "extractName('${show(text)}') returns ${expected?.let { "'$it'" } ?: "null"}" {
                AgentMention.extractName(text) shouldBe expected
            }
        }

        // input to what strip must return.
        val stripCases: List<Pair<String, String>> =
            listOf(
                // plain form
                "@Astra hello" to "hello",
                "@Astra" to "",
                // chip form: no `](#agent=...)` debris may remain
                "[@Astra](#agent=a8b12cb8-7d3a-42fe-a964-55cbb8f6e7aa) hello" to "hello",
                "[@Astra](#agent=x)" to "",
                "[@Astra] hello" to "hello",
                "[@Astra]hello" to "hello",
                "[@Casting Manager]() hello" to "hello",
                "[@Casting Manager]()" to "",
                "[@Casting Manager](#agent=x) what can you do?" to "what can you do?",
                "[@Astra](#agent=x)\nline two" to "line two",
                "[@Astra](#agent=x)\u00A0https://example.com" to "https://example.com",
                // the target is not parsed: one holding `)` leaves its tail in the body
                "[@Astra](#a)b) rest" to "b) rest",
                // no mention, or a malformed one: the text is returned as is
                "no mention here" to "no mention here",
                "@ foo" to "@ foo",
                "@@x" to "@@x",
                "@!x" to "@!x",
                "@" to "@",
                "[@Astra](#agent=x" to "[@Astra](#agent=x",
                "[@Astra](#a)(#b) hi" to "[@Astra](#a)(#b) hi",
            )

        stripCases.forEach { (text, expected) ->
            "strip('${show(text)}') returns '${show(expected)}'" {
                AgentMention.strip(text) shouldBe expected
            }
        }

        // extractName and strip share one grammar: what the first does not recognise, the second
        // must not remove.
        extractNameCases.filter { (_, expected) -> expected == null }.forEach { (text, _) ->
            "strip leaves '${show(text)}' untouched, as extractName rejects it" {
                AgentMention.strip(text) shouldBe text.trim()
            }
        }
    })
