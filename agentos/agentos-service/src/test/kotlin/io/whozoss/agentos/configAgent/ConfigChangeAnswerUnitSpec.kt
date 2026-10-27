package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import java.time.Instant
import java.util.UUID

class ConfigChangeAnswerUnitSpec :
    StringSpec({
        val answer = ConfigChangeAnswer(caseService = mockk(relaxed = true))
        val caller = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "a@example.com", email = "a@example.com", firstname = "Ada")

        fun operation(ref: String) =
            PlannedOperation(
                ref = ref,
                kind = ConfigKind.PROMPT,
                action = ConfigAction.CREATE,
                scope = PlannedScope(namespaceId = UUID.randomUUID(), userId = null),
                value = jacksonObjectMapper().readTree("""{"name":"$ref-prompt"}"""),
                affects = ConfigAffects.NAMESPACE_MEMBERS,
            )

        val plan =
            ConfigChangePlan(
                questionId = UUID.randomUUID(),
                caseId = UUID.randomUUID(),
                namespaceId = UUID.randomUUID(),
                recipientUserId = caller.id,
                agentName = "config",
                summary = "s",
                createdAt = Instant.EPOCH,
                expiresAt = Instant.EPOCH,
                order = listOf("ok", "bad", "skipped"),
                operations = listOf(operation("ok"), operation("bad"), operation("skipped")),
            )

        "a failed operation's message is cut to MAX_MESSAGE_LENGTH, and only failures carry one" {
            val longMessage = "x".repeat(ConfigChangeAnswer.MAX_MESSAGE_LENGTH + 50)
            val report =
                ConfigChangeReportDto(
                    decision = ConfigChangeDecision.PARTIAL,
                    appliedBy = caller.id,
                    results =
                        listOf(
                            ConfigOperationResultDto("ok", ConfigResultStatus.APPLIED, UUID.randomUUID(), message = "not shown when applied"),
                            ConfigOperationResultDto("bad", ConfigResultStatus.INVALID, message = longMessage),
                            ConfigOperationResultDto("skipped", ConfigResultStatus.NOT_RUN, message = "not shown when not run"),
                        ),
                )

            val text = answer.applied(plan, report, caller)

            text shouldContain "PARTIAL by Ada"
            text shouldContain "x".repeat(ConfigChangeAnswer.MAX_MESSAGE_LENGTH)
            text shouldNotContain "x".repeat(ConfigChangeAnswer.MAX_MESSAGE_LENGTH + 1)
            text shouldNotContain "not shown"
            text.lines().size shouldBe 4
        }
    })
