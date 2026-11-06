package io.whozoss.agentos.messaging

import io.whozoss.agentos.sdk.api.messaging.MessagingPlatform
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

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
    fun fakeVoiceTranscriber(): FakeVoiceTranscriber = FakeVoiceTranscriber()

    @Bean
    @Primary
    fun fakeMessagingDrivers(
        driver: FakeMessagingDriver,
        slack: FakeSlackDriver,
    ): MessagingDrivers = MessagingDrivers(listOf(driver, slack))
}

/** A [VoiceTranscriber] that reads the audio as the text it says, unless told to fail or to be off. */
class FakeVoiceTranscriber : VoiceTranscriber {
    @Volatile override var available: Boolean = true

    @Volatile var failure: String? = null

    override val maxDurationSeconds: Int = 60

    override val maxBytes: Long = 1024

    val transcribed = CopyOnWriteArrayList<String>()

    override fun transcribe(audio: ByteArray): String {
        failure?.let { throw TranscriptionException(it) }
        return audio.decodeToString().also { transcribed.add(it) }
    }

    fun reset() {
        available = true
        failure = null
        transcribed.clear()
    }
}

/** The fake standing for Slack, a type of its own so that a test can ask for it. */
class FakeSlackDriver : FakeMessagingDriver(MessagingPlatform.SLACK) {
    override fun appManifest(
        agentName: String,
        agentDescription: String?,
    ): AppManifest = AppManifest(manifest = """{"display_information":{"name":"$agentName"}}""", createUrl = "https://api.slack.com/apps?new_app=1")
}
