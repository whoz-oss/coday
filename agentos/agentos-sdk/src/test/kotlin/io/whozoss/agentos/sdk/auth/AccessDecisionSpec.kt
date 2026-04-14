package io.whozoss.agentos.sdk.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AccessDecisionSpec : StringSpec() {
    init {
        "Granted isGranted returns true" {
            AccessDecision.Granted("ADMIN").isGranted shouldBe true
        }
        "Denied isGranted returns false" {
            AccessDecision.Denied("not authorized").isGranted shouldBe false
        }
        "Abstain isGranted returns false" {
            AccessDecision.Abstain.isGranted shouldBe false
        }
        "Denied has default empty usersWithAccess" {
            AccessDecision.Denied("no access").usersWithAccess shouldBe emptyList()
        }
        "Denied can have usersWithAccess" {
            val denied = AccessDecision.Denied("no access", listOf("user1", "user2"))
            denied.usersWithAccess shouldBe listOf("user1", "user2")
        }
        "exhaustive when covers all variants" {
            val decisions = listOf(
                AccessDecision.Granted("OWNER"),
                AccessDecision.Denied("reason"),
                AccessDecision.Abstain,
            )
            decisions.forEach { decision ->
                when (decision) {
                    is AccessDecision.Granted -> decision.effectiveRole.isNotEmpty() shouldBe true
                    is AccessDecision.Denied -> decision.reason.isNotEmpty() shouldBe true
                    is AccessDecision.Abstain -> Unit
                }
            }
        }
    }
}
