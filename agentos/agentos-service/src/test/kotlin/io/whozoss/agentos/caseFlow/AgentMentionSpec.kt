package io.whozoss.agentos.caseFlow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AgentMentionSpec :
    StringSpec({
        // -------------------------------------------------------------------------
        // extractName: plain form
        // -------------------------------------------------------------------------

        "extractName returns the name for a plain mention with trailing text" {
            AgentMention.extractName("@Astra hello") shouldBe "Astra"
        }

        "extractName returns the name for a plain mention with no trailing text" {
            AgentMention.extractName("@Astra") shouldBe "Astra"
        }

        // -------------------------------------------------------------------------
        // extractName: chip form
        // -------------------------------------------------------------------------

        "extractName returns the name for a chip mention with trailing text" {
            AgentMention.extractName("[@Astra](#agent=a8b12cb8-7d3a-42fe-a964-55cbb8f6e7aa) hello") shouldBe "Astra"
        }

        "extractName returns the name for a chip mention with no trailing text" {
            AgentMention.extractName("[@Astra](#agent=a8b12cb8-7d3a-42fe-a964-55cbb8f6e7aa)") shouldBe "Astra"
        }

        "extractName returns the name for a chip mention with leading whitespace" {
            AgentMention.extractName("   [@Astra](#agent=x) hi") shouldBe "Astra"
        }

        "extractName returns the name for a chip mention with hyphens and underscores" {
            AgentMention.extractName("[@Casting-Manager_2](#agent=x) hi") shouldBe "Casting-Manager_2"
        }

        "extractName returns the name for a chip mention without a link target" {
            AgentMention.extractName("[@Astra] hello") shouldBe "Astra"
        }

        // -------------------------------------------------------------------------
        // extractName: regression guard (non-breaking space)
        // -------------------------------------------------------------------------

        "extractName stops at the non-breaking space and returns only the agent name" {
            // Regression: \S+ would capture the entire `inspector\u00A0https://...` string.
            // The fix [\w-]+ stops at the non-breaking space (U+00A0).
            AgentMention.extractName("@inspector\u00A0https://example.com") shouldBe "inspector"
        }

        // -------------------------------------------------------------------------
        // extractName: null cases
        // -------------------------------------------------------------------------

        // -------------------------------------------------------------------------
        // extractName: chip labels the plain grammar could not carry
        // -------------------------------------------------------------------------

        "extractName returns a chip label holding spaces" {
            AgentMention.extractName("[@Casting Manager](#agent=x) hi") shouldBe "Casting Manager"
        }

        "extractName returns a chip label holding dots" {
            AgentMention.extractName("[@v1.2 Reviewer](#agent=x) hi") shouldBe "v1.2 Reviewer"
        }

        "extractName returns a chip label holding non-ASCII letters" {
            AgentMention.extractName("[@\u00c9ric l'agent](#agent=x) hi") shouldBe "\u00c9ric l'agent"
        }

        "extractName ignores trailing blanks inside the chip label" {
            AgentMention.extractName("[@Astra ](#agent=x) hi") shouldBe "Astra"
        }

        "extractName stops a chip label at the first closing bracket" {
            AgentMention.extractName("[@Astra](#agent=x) see [docs](http://example.com)") shouldBe "Astra"
        }

        "extractName accepts a chip followed by a non-breaking space and text" {
            AgentMention.extractName("[@Astra](#agent=x)\u00A0https://example.com") shouldBe "Astra"
        }

        "extractName returns null for a chip label starting with whitespace" {
            AgentMention.extractName("[@ Astra](#agent=x) hi").shouldBeNull()
        }

        "extractName returns null for a chip label spanning lines" {
            AgentMention.extractName("[@Astra\nManager](#agent=x) hi").shouldBeNull()
        }

        "extractName returns null for an unclosed chip label" {
            AgentMention.extractName("[@Astra hello").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // extractName: plain form edges
        // -------------------------------------------------------------------------

        "extractName ignores leading whitespace before a plain mention" {
            AgentMention.extractName("  \n @Astra hi") shouldBe "Astra"
        }

        "extractName returns null for an at sign followed by a non-word character" {
            AgentMention.extractName("@!Astra").shouldBeNull()
            AgentMention.extractName("@").shouldBeNull()
        }

        "extractName stops a plain mention before a non-ASCII letter" {
            // `\w` is ASCII-only here: the name is cut where the \u00e9 starts.
            AgentMention.extractName("@\u00e9ric hi").shouldBeNull()
            AgentMention.extractName("@Eric\u00e9 hi") shouldBe "Eric"
        }

        "extractName returns null for a non-leading mention" {
            AgentMention.extractName("hello @Astra").shouldBeNull()
        }

        "extractName returns null for a chip without @" {
            AgentMention.extractName("[Astra](#agent=x)").shouldBeNull()
        }

        "extractName returns null for a chip with an empty name" {
            AgentMention.extractName("[@](#agent=x)").shouldBeNull()
        }

        "extractName returns null for a lone @ followed by whitespace" {
            AgentMention.extractName("@ something").shouldBeNull()
        }

        "extractName returns null for an empty string" {
            AgentMention.extractName("").shouldBeNull()
        }

        // -------------------------------------------------------------------------
        // extractName: malformed chips must be rejected, not half-accepted
        // -------------------------------------------------------------------------
        //
        // Without the `(?!\()` guard the optional link-target group would simply not
        // participate, the match would succeed on `[@Name]` alone, and `strip` would
        // leave the dangling link debris in the body handed to the LLM.

        "extractName returns null for a chip with an unclosed link target" {
            AgentMention.extractName("[@Astra](#agent=x").shouldBeNull()
        }

        "extractName returns null for a chip with a duplicated link target" {
            AgentMention.extractName("[@Astra](#a)(#b) hi").shouldBeNull()
        }

        "extractName returns null for a doubled opening bracket" {
            AgentMention.extractName("[[@Astra](#x)] hi").shouldBeNull()
        }

        "extractName returns null for a doubled at sign" {
            AgentMention.extractName("@@Astra hi").shouldBeNull()
        }

        "extractName accepts a chip immediately followed by text" {
            AgentMention.extractName("[@Astra]hello") shouldBe "Astra"
        }

        "extractName accepts a chip whose link target spans a newline" {
            AgentMention.extractName("[@Astra](#agent=\nfoo) hi") shouldBe "Astra"
        }

        "extractName accepts a digits-only name" {
            // `\w` includes digits, so `@123` is grammatically a mention. Resolution is
            // the responsibility of AgentService, which simply finds no such agent.
            AgentMention.extractName("@123 hi") shouldBe "123"
        }

        // -------------------------------------------------------------------------
        // strip: plain form
        // -------------------------------------------------------------------------

        "strip removes a plain mention and returns the remainder" {
            AgentMention.strip("@Astra hello") shouldBe "hello"
        }

        // -------------------------------------------------------------------------
        // strip: chip form
        // -------------------------------------------------------------------------

        "strip removes a chip mention and returns the remainder without residue" {
            // Critical: no `](#agent=…)` debris must remain.
            AgentMention.strip("[@Astra](#agent=a8b12cb8-7d3a-42fe-a964-55cbb8f6e7aa) hello") shouldBe "hello"
        }

        "strip removes a chip mention with no trailing text and returns empty string" {
            AgentMention.strip("[@Astra](#agent=x)") shouldBe ""
        }

        "strip removes a chip mention without a link target" {
            AgentMention.strip("[@Astra] hello") shouldBe "hello"
        }

        // -------------------------------------------------------------------------
        // strip: no mention
        // -------------------------------------------------------------------------

        "strip returns the text unchanged when there is no mention" {
            AgentMention.strip("no mention here") shouldBe "no mention here"
        }

        // -------------------------------------------------------------------------
        // strip: multiline
        // -------------------------------------------------------------------------

        "strip handles a chip mention followed by a newline and more text" {
            AgentMention.strip("[@Astra](#agent=x)\nline two") shouldBe "line two"
        }

        // -------------------------------------------------------------------------
        // strip / extractName agreement
        // -------------------------------------------------------------------------

        "strip leaves a malformed chip untouched, exactly as extractName rejects it" {
            // The two functions share one grammar and must never disagree: what
            // extractName does not recognise, strip must not remove.
            val malformed = "[@Astra](#agent=x"
            AgentMention.extractName(malformed).shouldBeNull()
            AgentMention.strip(malformed) shouldBe malformed
        }

        "strip removes a chip immediately followed by text" {
            AgentMention.strip("[@Astra]hello") shouldBe "hello"
        }

        "strip removes a chip whose label holds spaces" {
            AgentMention.strip("[@Casting Manager](#agent=x) what can you do?") shouldBe "what can you do?"
        }

        "strip removes a chip followed by a non-breaking space" {
            AgentMention.strip("[@Astra](#agent=x)\u00A0https://example.com") shouldBe "https://example.com"
        }

        "strip stops removing a chip link target at its first closing parenthesis" {
            // The target is not parsed: one holding `)` leaves its tail in the body. Pinned so a
            // change of grammar is a conscious one.
            AgentMention.extractName("[@Astra](#a)b) rest") shouldBe "Astra"
            AgentMention.strip("[@Astra](#a)b) rest") shouldBe "b) rest"
        }

        "strip leaves a text starting with an at sign that is not a mention untouched" {
            listOf("@ foo", "@@x", "@!x", "@").forEach { text ->
                AgentMention.extractName(text).shouldBeNull()
                AgentMention.strip(text) shouldBe text
            }
        }

        "strip removes a plain mention with no trailing text and returns empty string" {
            AgentMention.strip("@Astra") shouldBe ""
        }
    })
