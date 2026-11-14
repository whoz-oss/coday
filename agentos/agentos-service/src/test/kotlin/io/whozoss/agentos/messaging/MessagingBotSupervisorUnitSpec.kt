package io.whozoss.agentos.messaging

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.api.messaging.MessagingBotStatus
import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import java.util.UUID

/** The supervisor: what it cannot read must not stop the application, and a stopped connection reports nothing. */
class MessagingBotSupervisorUnitSpec :
    StringSpec({

        fun bot(enabled: Boolean = true) =
            MessagingBot(
                platform = MessagingPlatform.TELEGRAM,
                accountId = "4242",
                username = "helper_bot",
                displayName = "Helper",
                url = "https://t.me/helper_bot",
                token = "4242:secret",
                agentId = UUID.randomUUID(),
                namespaceId = UUID.randomUUID(),
                enabled = enabled,
            )

        "bots that cannot be read at startup are logged, and the application starts" {
            val repository = mockk<MessagingBotRepository> { every { findAll() } throws IllegalStateException("unreadable bot") }
            val supervisor = MessagingBotSupervisor(repository, mockk(relaxed = true), MessagingBotRegistry(), mockk(), mockk())

            shouldNotThrowAny { supervisor.startAll() }
        }

        "a connection stopped when its bot is turned off no longer reports a status" {
            val reporters = mutableListOf<(MessagingBotStatus) -> Unit>()
            val driver =
                mockk<MessagingDriver> {
                    every { platform } returns MessagingPlatform.TELEGRAM
                    every { start(any(), any(), any()) } answers {
                        reporters.add(thirdArg())
                        mockk(relaxed = true) { every { adapter } returns FakeMessagingAdapter() }
                    }
                }
            val registry = MessagingBotRegistry()
            val supervisor = MessagingBotSupervisor(mockk(), MessagingDrivers(listOf(driver)), registry, mockk(), mockk())
            val running = bot()

            supervisor.onBotChanged(MessagingBotChanged.Saved(running))
            reporters.single()(MessagingBotStatus.RUNNING)
            registry.status(running.id) shouldBe MessagingBotStatus.RUNNING

            supervisor.onBotChanged(MessagingBotChanged.Saved(running.copy(enabled = false)))
            reporters.single()(MessagingBotStatus.UNREACHABLE)

            registry.status(running.id) shouldBe MessagingBotStatus.DISABLED
        }
    })
