package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Runs the bots of a test on [FakeMessagingDriver]s instead of Telegram and Slack. */
@TestConfiguration
class FakeMessagingConfiguration {
    @Bean
    @Primary
    fun fakeMessagingDriver(): FakeMessagingDriver = FakeMessagingDriver()

    @Bean
    fun fakeSlackDriver(): FakeSlackDriver = FakeSlackDriver()

    @Bean
    @Primary
    fun fakeMessagingDrivers(
        driver: FakeMessagingDriver,
        slack: FakeSlackDriver,
    ): MessagingDrivers = MessagingDrivers(listOf(driver, slack))
}

/** The fake standing for Slack, a type of its own so that a test can ask for it. */
class FakeSlackDriver : FakeMessagingDriver(MessagingPlatform.SLACK) {
    override fun appManifest(
        agentName: String,
        agentDescription: String?,
    ): AppManifest = AppManifest(manifest = """{"display_information":{"name":"$agentName"}}""", createUrl = "https://api.slack.com/apps?new_app=1")
}
