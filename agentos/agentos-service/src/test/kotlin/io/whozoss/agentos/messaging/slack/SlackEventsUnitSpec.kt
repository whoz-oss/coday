package io.whozoss.agentos.messaging.slack

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.messaging.MessagingAction
import io.whozoss.agentos.messaging.MessagingAudio
import io.whozoss.agentos.messaging.MessagingInbound
import io.whozoss.agentos.messaging.MessagingSender
import java.util.UUID

class SlackEventsUnitSpec :
    StringSpec({

        val mapper = jacksonObjectMapper()

        fun envelope(json: String): SlackEvents.Frame.Envelope = SlackEvents.read(mapper, json).shouldBeInstanceOf<SlackEvents.Frame.Envelope>()

        fun message(event: String) =
            envelope("""{"envelope_id":"e1","type":"events_api","payload":{"type":"event_callback","event":$event}}""")

        "a connection says hello, then hands envelopes to acknowledge by their id" {
            SlackEvents.read(mapper, """{"type":"hello","num_connections":1}""") shouldBe SlackEvents.Frame.Hello
            envelope("""{"envelope_id":"abc-1","type":"events_api","payload":{}}""").ack shouldBe """{"envelope_id":"abc-1"}"""
            SlackEvents.read(mapper, """{"type":"disconnect","reason":"refresh_requested"}""") shouldBe
                SlackEvents.Frame.Disconnect("refresh_requested")
            SlackEvents.read(mapper, "not json") shouldBe SlackEvents.Frame.Other
        }

        "a message of a person in the bot's direct chat is a text, unescaped, numbered by its timestamp" {
            val event =
                """{"type":"message","channel":"D1","channel_type":"im","user":"U7","ts":"1700000000.000200",""" +
                    """"text":"a &lt; b &amp;&amp; see <https://example.com|the docs>"}"""

            SlackEvents.inbound(message(event), botUserId = "UBOT") shouldBe
                MessagingInbound.Text(
                    chatId = "D1",
                    sender = MessagingSender("U7", "U7", bot = false),
                    messageId = 1700000000000200,
                    text = "a < b && see the docs (https://example.com)",
                    direct = true,
                )
        }

        "the bot's own messages, edits and the messages of other bots are nothing" {
            val base = """"channel":"D1","channel_type":"im","ts":"1.000001","text":"hi""""

            SlackEvents.inbound(message("""{"type":"message","user":"UBOT",$base}"""), "UBOT") shouldBe null
            SlackEvents.inbound(message("""{"type":"message","user":"U7","subtype":"message_changed",$base}"""), "UBOT") shouldBe null
            SlackEvents.inbound(message("""{"type":"message","user":"U8","bot_id":"B1",$base}"""), "UBOT") shouldBe null
        }

        "a clip recorded in Slack is a voice message, with its download URL and its duration rounded up" {
            val event =
                """{"type":"message","subtype":"file_share","channel":"D1","channel_type":"im","user":"U7","ts":"1700000000.000300","text":"",""" +
                    """"files":[{"id":"F1","subtype":"slack_audio","mimetype":"audio/webm","size":5120,"duration_ms":4100,""" +
                    """"url_private_download":"https://files.slack.com/files-pri/T1-F1/download/audio_message.webm"}]}"""

            SlackEvents.inbound(message(event), botUserId = "UBOT") shouldBe
                MessagingInbound.Voice(
                    chatId = "D1",
                    sender = MessagingSender("U7", "U7", bot = false),
                    messageId = 1700000000000300,
                    audio =
                        MessagingAudio(
                            ref = "https://files.slack.com/files-pri/T1-F1/download/audio_message.webm",
                            mimeType = "audio/webm",
                            durationSeconds = 5,
                            sizeBytes = 5120,
                        ),
                    direct = true,
                )
        }

        "a file that is not audio is a message the bot cannot read; one without its URLs is downloaded by its id" {
            val image =
                """{"type":"message","subtype":"file_share","channel":"D1","channel_type":"im","user":"U7","ts":"1.000004",""" +
                    """"files":[{"id":"F2","mimetype":"image/png","url_private":"https://files.slack.com/files-pri/T1-F2/x.png"}]}"""
            val unseen =
                """{"type":"message","channel":"D1","channel_type":"im","user":"U7","ts":"1.000005",""" +
                    """"files":[{"id":"F3","mimetype":"audio/mp4","file_access":"check_file_info"}]}"""

            SlackEvents.inbound(message(image), "UBOT") shouldBe
                MessagingInbound.Unsupported(chatId = "D1", sender = MessagingSender("U7", "U7", bot = false), messageId = 1000004, direct = true)
            (SlackEvents.inbound(message(unseen), "UBOT") as MessagingInbound.Voice).audio.ref shouldBe "F3"
        }

        "a message outside a direct chat is not direct" {
            val event = """{"type":"message","channel":"C1","channel_type":"channel","user":"U7","ts":"1.000001","text":"hi"}"""

            (SlackEvents.inbound(message(event), "UBOT") as MessagingInbound.Text).direct shouldBe false
        }

        "a click on a button is a click, with what it encodes; a link button is nothing" {
            val questionId = UUID.randomUUID()
            val click =
                envelope(
                    """{"envelope_id":"e2","type":"interactive","payload":{"type":"block_actions","user":{"id":"U7"},""" +
                        """"channel":{"id":"D1"},"message":{"ts":"1700000000.000300"},""" +
                        """"actions":[{"action_id":"action_0","value":"q:$questionId:1"}]}}""",
                )
            val link =
                envelope(
                    """{"envelope_id":"e3","type":"interactive","payload":{"type":"block_actions","user":{"id":"U7"},""" +
                        """"channel":{"id":"D1"},"message":{"ts":"1.000001"},"actions":[{"action_id":"url_0","url":"https://x"}]}}""",
                )

            SlackEvents.inbound(click, "UBOT") shouldBe
                MessagingInbound.Click(
                    chatId = "D1",
                    sender = MessagingSender("U7", "U7", bot = false),
                    clickId = "D1:U7",
                    messageId = "1700000000.000300",
                    action = MessagingAction.AnswerQuestion(questionId, 1),
                )
            SlackEvents.inbound(link, "UBOT") shouldBe null
        }
    })
