package io.whozoss.agentos.sdk.spi

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.sdk.tool.ToolContext
import java.util.UUID

/**
 * Guards the fail-closed contract of [ToolGrantPolicy.isGranted].
 *
 * A policy exists only to restrict what an agent may do. The decisive test here is
 * [a policy that throws denies the tool]: if it ever flips to granting, a faulty plugin
 * silently hands an agent the very tools the policy was installed to withhold.
 */
class ToolGrantPolicySpec : StringSpec({

    val context =
        ToolContext(
            namespaceId = UUID.randomUUID(),
            userId = null,
            userExternalId = null,
            caseEvents = emptyList(),
            agentName = "agent",
        )

    fun policy(decide: (String) -> ToolGrantDecision) =
        object : ToolGrantPolicy {
            override fun evaluateToolGrant(toolName: String, context: ToolContext) =
                decide(toolName)
        }

    "no policy registered grants everything" {
        ToolGrantPolicy.isGranted(emptyList(), "anyTool", context) shouldBe true
    }

    "a neutral policy leaves the tool granted" {
        val policies = listOf(policy { ToolGrantDecision.Neutral })

        ToolGrantPolicy.isGranted(policies, "readFile", context) shouldBe true
    }

    // The invariant this whole type exists for.
    "a policy that throws denies the tool" {
        val faulty =
            object : ToolGrantPolicy {
                override fun evaluateToolGrant(toolName: String, context: ToolContext) =
                    throw IllegalStateException("policy backend unreachable")
            }
        var reason: String? = null

        val granted =
            ToolGrantPolicy.isGranted(listOf(faulty), "editFiles", context) { r, _, _ -> reason = r }

        granted shouldBe false
        reason!! shouldContain "fail-closed"
    }

    "a faulty policy denies even when another policy is neutral" {
        val faulty =
            object : ToolGrantPolicy {
                override fun evaluateToolGrant(toolName: String, context: ToolContext) =
                    throw RuntimeException("boom")
            }
        val permissive = policy { ToolGrantDecision.Neutral }

        ToolGrantPolicy.isGranted(listOf(faulty, permissive), "editFiles", context) shouldBe false
        ToolGrantPolicy.isGranted(listOf(permissive, faulty), "editFiles", context) shouldBe false
    }

    "AllowOnly denies a tool outside the allow-list and grants one inside it" {
        val policies = listOf(policy { ToolGrantDecision.AllowOnly(setOf("readFile", "ls")) })

        ToolGrantPolicy.isGranted(policies, "readFile", context) shouldBe true
        ToolGrantPolicy.isGranted(policies, "editFiles", context) shouldBe false
    }

    "Deny denies a listed tool and leaves others untouched" {
        val policies = listOf(policy { ToolGrantDecision.Deny(setOf("editFiles"), "read-only run") })

        ToolGrantPolicy.isGranted(policies, "editFiles", context) shouldBe false
        ToolGrantPolicy.isGranted(policies, "readFile", context) shouldBe true
    }

    "the denial reason is surfaced when a policy provides one" {
        val policies = listOf(policy { ToolGrantDecision.Deny(setOf("editFiles"), "read-only run") })
        var reason: String? = null

        ToolGrantPolicy.isGranted(policies, "editFiles", context) { r, _, _ -> reason = r }

        reason shouldBe "read-only run"
    }

    // A tool allowed by one policy may still be denied by another: denial always wins.
    "one denial outweighs any number of permissive policies" {
        val policies =
            listOf(
                policy { ToolGrantDecision.AllowOnly(setOf("editFiles")) },
                policy { ToolGrantDecision.Neutral },
                policy { ToolGrantDecision.Deny(setOf("editFiles")) },
            )

        ToolGrantPolicy.isGranted(policies, "editFiles", context) shouldBe false
    }

    "evaluation short-circuits on the first denial" {
        var consulted = 0
        val denying = policy { ToolGrantDecision.Deny(setOf("editFiles")) }
        val counting =
            object : ToolGrantPolicy {
                override fun evaluateToolGrant(toolName: String, context: ToolContext): ToolGrantDecision {
                    consulted++
                    return ToolGrantDecision.Neutral
                }
            }

        ToolGrantPolicy.isGranted(listOf(denying, counting), "editFiles", context) shouldBe false
        consulted shouldBe 0
    }

    "the default implementation is neutral" {
        val bare = object : ToolGrantPolicy {}

        bare.evaluateToolGrant("anyTool", context) shouldBe ToolGrantDecision.Neutral
    }
})
