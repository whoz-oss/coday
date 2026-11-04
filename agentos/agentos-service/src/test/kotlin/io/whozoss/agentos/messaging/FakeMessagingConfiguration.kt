package io.whozoss.agentos.messaging

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Runs the bots of a test on a [FakeMessagingDriver] instead of Telegram. */
@TestConfiguration
class FakeMessagingConfiguration {
    @Bean
    fun fakeMessagingDriver(): FakeMessagingDriver = FakeMessagingDriver()

    @Bean
    @Primary
    fun fakeMessagingDrivers(driver: FakeMessagingDriver): MessagingDrivers = MessagingDrivers(listOf(driver))
}
