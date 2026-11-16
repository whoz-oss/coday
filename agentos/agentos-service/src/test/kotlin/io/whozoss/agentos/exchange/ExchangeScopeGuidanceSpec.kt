package io.whozoss.agentos.exchange

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.whozoss.agentos.exchange.ExchangeScopeGuidance.Access

/**
 * Unit tests for [ExchangeScopeGuidance]: when the `## File scopes` block is emitted at all, and
 * what each combination of accesses says.
 *
 * The length assertion on the tool guidance is the one that earns its keep: providers cap a tool
 * description (OpenAI rejects one past 1024 characters) and the file-plugin's `readSpreadsheet`
 * description is already within ~60 characters of that ceiling, so a sentence that grew here would
 * break a production run on a provider the test suite does not call.
 */
class ExchangeScopeGuidanceSpec : StringSpec({

    // -------------------------------------------------------------------------
    // Emission — a single surface cannot be confused with another
    // -------------------------------------------------------------------------

    "no block when neither scope was granted" {
        ExchangeScopeGuidance.buildBlock(case = null, namespace = null).shouldBeNull()
    }

    "no block when only the case scope was granted" {
        ExchangeScopeGuidance.buildBlock(case = Access.READ_WRITE, namespace = null).shouldBeNull()
    }

    "no block when only the namespace scope was granted" {
        ExchangeScopeGuidance.buildBlock(case = null, namespace = Access.READ_WRITE).shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // Content
    // -------------------------------------------------------------------------

    "the block opens on its own heading, after a blank line like every other instructions block" {
        val block = ExchangeScopeGuidance.buildBlock(Access.READ_WRITE, Access.READ_WRITE)

        block.shouldNotBeNull()
        block shouldStartWith "\n## File scopes"
    }

    "both scopes writable: the case is the default target and the namespace needs an explicit ask" {
        val block = ExchangeScopeGuidance.buildBlock(Access.READ_WRITE, Access.READ_WRITE)

        block.shouldNotBeNull()
        // The prefixes are what the model actually reads on a tool name, so they are named verbatim.
        block shouldContain "`${ExchangeIntegrationTypes.CASE_CONFIG_NAME}__`"
        block shouldContain "`${ExchangeIntegrationTypes.NAMESPACE_CONFIG_NAME}__`"
        block shouldContain "Work in the case scope by default"
        // Criterion of WZ-35527: reads must land in the worktree too, and on the Forge server the
        // namespace scope holds a second copy of the very same repository.
        block shouldContain "the case copy is the one your task is about"
        block shouldContain "Reach for the namespace scope only when the user asks you"
        block shouldContain "Follow your own instructions where they say otherwise."
    }

    "a read-only namespace scope is never presented as a write target" {
        val block = ExchangeScopeGuidance.buildBlock(Access.READ_WRITE, Access.READ_ONLY)

        block.shouldNotBeNull()
        block shouldContain "The namespace scope is read-only for you"
        block shouldNotContain "Reach for the namespace scope"
    }

    "a read-only case scope does not turn the namespace scope into the fallback" {
        // Reachable on a sub-case running in a family workspace owned by another case the user
        // cannot write. Telling the agent to write somewhere shared because its own scope is locked
        // is the exact failure this block exists to prevent.
        val block = ExchangeScopeGuidance.buildBlock(Access.READ_ONLY, Access.READ_WRITE)

        block.shouldNotBeNull()
        block shouldContain "The case scope is read-only for you on this run"
        block shouldContain "rather than falling back to the namespace scope"
        block shouldNotContain "Work in the case scope by default"
    }

    // -------------------------------------------------------------------------
    // The tool-description sentences
    // -------------------------------------------------------------------------

    "the write-tool guidance stays far enough under the provider description cap" {
        // The longest description one of these is appended to is editFiles (325 characters at the
        // time of writing), against a 1024-character cap. 300 leaves room for both to grow without
        // anyone having to recompute the sum.
        ExchangeScopeGuidance.CASE_TOOL_GUIDANCE.length shouldBeLessThanOrEqual 300
        ExchangeScopeGuidance.NAMESPACE_TOOL_GUIDANCE.length shouldBeLessThanOrEqual 300
    }

    "each write-tool sentence names its own scope, so two identical descriptions stop being identical" {
        ExchangeScopeGuidance.CASE_TOOL_GUIDANCE shouldContain "case"
        ExchangeScopeGuidance.NAMESPACE_TOOL_GUIDANCE shouldContain "namespace"
    }

    "no write-tool sentence names the other scope as a fallback" {
        // A constant cannot know the other scope's stance. On a sub-case whose family workspace is
        // owned by a case the invoking user cannot write, the case scope is read-only and the
        // namespace one is writable, so a sentence sending the model back to the case scope would
        // contradict the block rendered in the same prompt. Naming a default target is the block's
        // job, since it varies with the access.
        ExchangeScopeGuidance.NAMESPACE_TOOL_GUIDANCE shouldNotContain "work in the case scope"
        ExchangeScopeGuidance.CASE_TOOL_GUIDANCE shouldNotContain "namespace"
    }
})
