package io.whozoss.agentos.configAgent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the wire names of the config agent. The frontend cannot import them and keeps its own copies:
 * `case-chat/config-change-questions.ts` (the plan schema, to recognise a plan and show its card) and
 * `agent-config-form/config-agent-exposure.ts` (the integration type, for the exposure warning).
 * Change them together.
 */
class ConfigAgentIntegrationUnitSpec :
    StringSpec({
        "the names the frontend mirrors stay stable" {
            ConfigAgentIntegration.TYPE shouldBe "AGENTOS_CONFIG"
            ConfigAgentIntegration.PLAN_SCHEMA shouldBe "agentos.config-change/1"
            ConfigAgentIntegration.PROPOSE_TOOL_NAME shouldBe "AGENTOS_CONFIG__propose"
            ConfigAgentIntegration.READ_TOOL_NAME shouldBe "AGENTOS_CONFIG__read"
        }
    })
