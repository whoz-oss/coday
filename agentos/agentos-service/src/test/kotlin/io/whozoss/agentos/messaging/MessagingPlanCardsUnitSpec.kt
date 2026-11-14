package io.whozoss.agentos.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.sdk.api.configChange.ConfigAction
import io.whozoss.agentos.sdk.api.configChange.ConfigAffects
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeDecision
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeReportDto
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeState
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeViewDto
import io.whozoss.agentos.sdk.api.configChange.ConfigFieldChangeDto
import io.whozoss.agentos.sdk.api.configChange.ConfigKind
import io.whozoss.agentos.sdk.api.configChange.ConfigOperationResultDto
import io.whozoss.agentos.sdk.api.configChange.ConfigOperationStatus
import io.whozoss.agentos.sdk.api.configChange.ConfigOperationViewDto
import io.whozoss.agentos.sdk.api.configChange.ConfigResultStatus
import io.whozoss.agentos.sdk.api.configChange.ConfigSecretInputDto
import java.time.Instant
import java.util.UUID

class MessagingPlanCardsUnitSpec : StringSpec() {
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val ada = UUID.randomUUID()

    private fun view(
        vararg operations: ConfigOperationViewDto,
        state: ConfigChangeState = ConfigChangeState.OPEN,
        recipient: UUID = ada,
    ) = ConfigChangeViewDto(
        questionId = UUID.randomUUID(),
        caseId = UUID.randomUUID(),
        recipientUserId = recipient,
        agentName = "Configurator",
        summary = "Give Assistant a GitHub integration.",
        planHash = "hash",
        createdAt = now,
        expiresAt = now.plusSeconds(30 * 60),
        state = state,
        canApply = state == ConfigChangeState.OPEN && recipient == ada,
        canReject = state in setOf(ConfigChangeState.OPEN, ConfigChangeState.EXPIRED) && recipient == ada,
        operations = operations.toList(),
        warnings = listOf("Assistant will reach GitHub with your token."),
    )

    init {
        "a plan shows each operation, its fields before and after, the warnings, and Apply with a confirmation" {
            val plan = view(updateDescription(), createIntegration(order = 2))

            val card = MessagingPlanCards.card(plan, ada, now, caseLink = "https://agentos.example.com/case")

            card.text shouldContain "Give Assistant a GitHub integration."
            card.text shouldContain "1. **Update agent Assistant**\n• `/description`: `old` → `new`"
            card.text shouldContain "2. **Create integration GitHub, copy of GitHub prod**\n• `integrationType`: `GITHUB`"
            card.text shouldContain "⚠️ Assistant will reach GitHub with your token."
            card.text shouldContain "_Expires in 30 min._"
            card.buttons.map { it.label } shouldBe listOf("Apply", "Reject", "Open in AgentOS")
            card.buttons.first().confirm shouldContain "with your rights"
        }

        "a plan that needs a secret is not applied from the chat, and can still be rejected" {
            val plan = view(createIntegration(order = 1, secret = true))

            val card = MessagingPlanCards.card(plan, ada, now, caseLink = null)

            card.text shouldContain "🔑 API token: to enter in AgentOS"
            card.text shouldContain MessagingPlanCards.SECRET_TEXT
            card.buttons.map { it.action } shouldBe listOf(MessagingAction.RejectPlan(plan.questionId))
        }

        "someone the plan was not proposed to sees it without buttons" {
            val plan = view(updateDescription(), recipient = UUID.randomUUID())

            val card = MessagingPlanCards.card(plan, ada, now, caseLink = null)

            card.text shouldContain MessagingPlanCards.NOT_RECIPIENT_TEXT
            card.buttons shouldBe emptyList()
        }

        "a plan already answered says so" {
            val card = MessagingPlanCards.card(view(updateDescription(), state = ConfigChangeState.ANSWERED), ada, now, caseLink = null)

            card.text shouldContain "already applied or rejected"
            card.buttons shouldBe emptyList()
        }

        "a long value is cut, and backticks cannot break out of its code span" {
            val long = updateDescription(after = "x`".repeat(200))

            val text = MessagingPlanCards.card(view(long), ada, now, caseLink = null).text

            text shouldContain "`" + "x'".repeat(60) + "…`"
            text shouldNotContain "x`x"
        }

        "the report of an apply tells each operation's outcome, with the refusal of the REST layer" {
            val plan = view(updateDescription(), createIntegration(order = 2))
            val report =
                ConfigChangeReportDto(
                    decision = ConfigChangeDecision.PARTIAL,
                    appliedBy = ada,
                    results =
                        listOf(
                            ConfigOperationResultDto("op1", ConfigResultStatus.APPLIED),
                            ConfigOperationResultDto("op2", ConfigResultStatus.DENIED, message = "The user is not allowed to do this."),
                        ),
                )

            MessagingPlanCards.applied(plan, report, "Ada Lovelace") shouldBe
                "⚠️ **Plan partly applied** by Ada Lovelace\n" +
                "1. Update agent Assistant: done\n" +
                "2. Create integration GitHub, copy of GitHub prod: refused (The user is not allowed to do this.)"
        }
    }

    companion object {
        private val json = ObjectMapper()

        fun updateDescription(after: String = "new") =
            ConfigOperationViewDto(
                ref = "op1",
                order = 1,
                kind = ConfigKind.AGENT,
                action = ConfigAction.UPDATE,
                name = "Assistant",
                targetId = UUID.randomUUID(),
                namespaceId = UUID.randomUUID(),
                userId = null,
                affects = ConfigAffects.NAMESPACE_MEMBERS,
                deployTo = null,
                value = null,
                changes = listOf(ConfigFieldChangeDto(path = "/description", before = json.nodeFactory.textNode("old"), after = json.nodeFactory.textNode(after), remove = false)),
                secretInputs = emptyList(),
                status = ConfigOperationStatus.PENDING,
            )

        fun createIntegration(
            order: Int,
            secret: Boolean = false,
        ) = ConfigOperationViewDto(
            ref = "op$order",
            order = order,
            kind = ConfigKind.INTEGRATION,
            action = ConfigAction.CREATE,
            name = "GitHub",
            targetId = null,
            namespaceId = UUID.randomUUID(),
            userId = null,
            affects = ConfigAffects.NAMESPACE_MEMBERS,
            deployTo = null,
            copyFrom = "GitHub prod",
            value = json.createObjectNode().put("name", "GitHub").put("integrationType", "GITHUB"),
            changes = emptyList(),
            secretInputs = if (secret) listOf(ConfigSecretInputDto(key = "op$order./parameters/token", path = "/parameters/token", label = "API token")) else emptyList(),
            status = ConfigOperationStatus.PENDING,
        )
    }
}
