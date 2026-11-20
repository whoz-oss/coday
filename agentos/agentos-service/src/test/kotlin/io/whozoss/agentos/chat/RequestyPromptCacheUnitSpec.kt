package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

class RequestyPromptCacheUnitSpec :
    StringSpec({
        val mapper = ObjectMapper()

        fun body(json: String): ObjectNode = mapper.readTree(json) as ObjectNode

        fun json(json: String): JsonNode = mapper.readTree(json)

        val oneHour = json("""{"type":"ephemeral","ttl":"1h"}""")

        fun ObjectNode.message(index: Int): JsonNode = get("messages").get(index)

        // JsonNode is Iterable: kotest's shouldBe would compare the values only, not the keys.
        infix fun JsonNode?.shouldBeJson(expected: JsonNode) {
            withClue("expected $expected but was $this") { (this == expected) shouldBe true }
        }

        fun JsonNode.cacheControlCount(): Int =
            (if (has("cache_control")) 1 else 0) + elements().asSequence().sumOf { it.cacheControlCount() }

        /** The body after a pass that must have placed markers. */
        fun marked(json: String): ObjectNode {
            val body = body(json)
            withClue("expected markers to be placed in $json") { RequestyPromptCache.addOneHourCacheControl(body) shouldBe true }
            return body
        }

        /** Asserts a pass leaves the body exactly as it was. */
        fun assertUntouched(json: String) {
            val body = body(json)
            val snapshot = body.deepCopy()
            withClue("expected $json to be left as is") { RequestyPromptCache.addOneHourCacheControl(body) shouldBe false }
            body shouldBeJson snapshot
        }

        // ---------------------------------------------------------------------------------------------
        // isRequesty / isClaudeModel
        // ---------------------------------------------------------------------------------------------

        "isRequesty matches the Requesty router and its subdomains" {
            listOf(
                "https://router.eu.requesty.ai",
                "https://router.requesty.ai/v1",
                "https://requesty.ai",
                "HTTPS://ROUTER.REQUESTY.AI",
                "https://router.requesty.ai:443/v1",
                "https://router.requesty.ai.",
                "https://router.requesty.ai./v1",
                " https://router.requesty.ai ",
                // Userinfo before the host: the request still goes to Requesty.
                "https://evil.com@router.requesty.ai",
            ).forEach { url -> withClue(url) { RequestyPromptCache.isRequesty(url) shouldBe true } }
        }

        "isRequesty rejects every other host" {
            listOf(
                "https://api.openai.com",
                "https://evilrequesty.ai",
                "https://notrequesty.ai",
                "https://requesty.ai.evil.com",
                "https://router.requesty.ai.evil.com:443",
                // Userinfo trick: the host is evil.com.
                "https://requesty.ai@evil.com",
                "https://router.requesty.ai:443@evil.com/v1",
                "http://127.0.0.1:8080",
                "not a url",
                "",
                "   ",
                null,
            ).forEach { url -> withClue(url.toString()) { RequestyPromptCache.isRequesty(url) shouldBe false } }
        }

        "the default model pattern matches Claude model names, whatever the prefix or case" {
            val properties = RequestyPromptCacheProperties()

            properties.matchesModel("anthropic/claude-sonnet-4-5") shouldBe true
            properties.matchesModel("policy/claude-opus") shouldBe true
            properties.matchesModel("bedrock/Claude-Haiku") shouldBe true
            properties.matchesModel("openai/gpt-5") shouldBe false
            properties.matchesModel("policy/sonnet-with-fallback") shouldBe false
            properties.matchesModel("") shouldBe false
            properties.matchesModel(null) shouldBe false
        }

        "a custom model pattern can add Claude-backed policies" {
            val properties = RequestyPromptCacheProperties(modelPattern = "(?i)claude|^policy/sonnet-with-fallback$")

            properties.matchesModel("policy/sonnet-with-fallback") shouldBe true
            properties.matchesModel("anthropic/claude-sonnet-4-5") shouldBe true
            properties.matchesModel("policy/sonnet-with-fallback-2") shouldBe false
            properties.matchesModel("openai/gpt-5") shouldBe false
        }

        "an invalid model pattern fails when the properties are built" {
            shouldThrowAny { RequestyPromptCacheProperties(modelPattern = "(unclosed") }
        }

        // ---------------------------------------------------------------------------------------------
        // addOneHourCacheControl
        // ---------------------------------------------------------------------------------------------

        "a string system message and the last user message get a one-hour marker, and auto caching is turned off" {
            val result =
                marked(
                    """{"model":"anthropic/claude-sonnet-4-5","messages":[
                    {"role":"system","content":"You are helpful."},
                    {"role":"user","content":"Hello"}],"temperature":0.7}""",
                )

            result.get("requesty") shouldBeJson json("""{"auto_cache":false}""")
            result.message(0).get("content") shouldBeJson
                json("""[{"type":"text","text":"You are helpful.","cache_control":{"type":"ephemeral","ttl":"1h"}}]""")
            result.message(1).get("content") shouldBeJson
                json("""[{"type":"text","text":"Hello","cache_control":{"type":"ephemeral","ttl":"1h"}}]""")
            result.get("model").asText() shouldBe "anthropic/claude-sonnet-4-5"
            result.get("temperature").asDouble() shouldBe 0.7
        }

        "a system message already made of parts gets the marker on its last text part" {
            val result =
                marked(
                    """{"messages":[
                    {"role":"system","content":[{"type":"text","text":"Part one"},{"type":"text","text":"Part two"}]},
                    {"role":"user","content":"Hello"}]}""",
                )

            val parts = result.message(0).get("content")
            parts.get(0).has("cache_control") shouldBe false
            parts.get(1).get("cache_control") shouldBeJson oneHour
        }

        "only the last of several system messages is marked" {
            val result =
                marked(
                    """{"messages":[
                    {"role":"system","content":"Agent prompt"},
                    {"role":"system","content":"Namespace context"},
                    {"role":"user","content":"Hello"}]}""",
                )

            result.message(0).get("content").isTextual shouldBe true
            result.message(1).get("content").get(0).get("cache_control") shouldBeJson oneHour
            result.cacheControlCount() shouldBe 2
        }

        "a system message that is also the last message with text gets a single marker" {
            val result = marked("""{"messages":[{"role":"system","content":"Only a system prompt"}]}""")

            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
            result.cacheControlCount() shouldBe 1
        }

        "a system message with null content is skipped, the last message with text is still marked" {
            val result = marked("""{"messages":[{"role":"system","content":null},{"role":"user","content":"Hello"}]}""")

            result.message(0).get("content").isNull shouldBe true
            result.message(1).get("content").get(0).get("cache_control") shouldBeJson oneHour
            result.cacheControlCount() shouldBe 1
        }

        "a last tool message is the one marked after the system message" {
            val result =
                marked(
                    """{"messages":[
                    {"role":"system","content":"System"},
                    {"role":"user","content":"Weather in Paris?"},
                    {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{}"}}]},
                    {"role":"tool","tool_call_id":"call_1","content":"sunny"}]}""",
                )

            result.message(3).get("content") shouldBeJson
                json("""[{"type":"text","text":"sunny","cache_control":{"type":"ephemeral","ttl":"1h"}}]""")
            result.message(3).get("tool_call_id").asText() shouldBe "call_1"
            result.message(1).get("content").isTextual shouldBe true
            result.cacheControlCount() shouldBe 2
        }

        "a last assistant message with text and tool calls gets the marker on its text, its tool calls kept" {
            val toolCalls = """[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]"""
            val result =
                marked(
                    """{"messages":[{"role":"user","content":"Go"},
                    {"role":"assistant","content":"Let me check.","tool_calls":$toolCalls}]}""",
                )

            result.message(1).get("content") shouldBeJson
                json("""[{"type":"text","text":"Let me check.","cache_control":{"type":"ephemeral","ttl":"1h"}}]""")
            result.message(1).get("tool_calls") shouldBeJson json(toolCalls)
            result.message(0).get("content").isTextual shouldBe true
        }

        "a user message with media gets the marker on its last text part, the image is left alone" {
            val result =
                marked(
                    """{"messages":[{"role":"user","content":[
                    {"type":"text","text":"What is on this picture?"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}""",
                )

            val parts = result.message(0).get("content")
            parts.get(0).get("cache_control") shouldBeJson oneHour
            parts.get(1) shouldBeJson json("""{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}""")
        }

        "an image-only last user message is skipped, the previous message with text is marked" {
            val image = """{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}"""
            val result = marked("""{"messages":[{"role":"user","content":"Look at this"},$image]}""")

            result.message(1) shouldBeJson json(image)
            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
        }

        "an assistant message that only carries tool calls is never touched" {
            val assistant =
                """{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]}"""
            val result = marked("""{"messages":[{"role":"user","content":"Go"},$assistant]}""")

            result.message(1) shouldBeJson json(assistant)
            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
        }

        "messages with empty text are skipped" {
            val result = marked("""{"messages":[{"role":"user","content":"Hello"},{"role":"user","content":""}]}""")

            result.message(1).get("content").asText() shouldBe ""
            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
        }

        "applying it twice changes nothing" {
            val once =
                marked(
                    """{"messages":[
                    {"role":"system","content":"System"},
                    {"role":"user","content":[{"type":"text","text":"Hi"},{"type":"image_url","image_url":{"url":"x"}}]}],
                    "requesty":{"tags":["a"]}}""",
                )
            val snapshot = once.deepCopy()

            RequestyPromptCache.addOneHourCacheControl(once) shouldBe false
            once shouldBeJson snapshot
            once.cacheControlCount() shouldBe 2
        }

        "existing requesty fields are kept, auto_cache is forced to false" {
            val result =
                marked(
                    """{"messages":[{"role":"user","content":"Hello"}],
                    "requesty":{"auto_cache":true,"tags":["agentos"],"trace_id":"t-1"}}""",
                )

            result.get("requesty") shouldBeJson json("""{"auto_cache":false,"tags":["agentos"],"trace_id":"t-1"}""")
        }

        "a null requesty field is replaced by the auto_cache option" {
            marked("""{"messages":[{"role":"user","content":"Hello"}],"requesty":null}""").get("requesty") shouldBeJson
                json("""{"auto_cache":false}""")
        }

        "unicode content survives the rewrite" {
            val text = "Café 日本語 🎉 « quotes »"
            val rewritten =
                RequestyPromptCache.rewriteChatCompletionsBody(
                    "/v1/chat/completions",
                    mapper.writeValueAsBytes(mapOf("messages" to listOf(mapOf("role" to "user", "content" to text)))),
                )

            mapper.readTree(rewritten).get("messages").get(0).get("content").get(0).get("text").asText() shouldBe text
        }

        "a tool parameter named cache_control does not count as a marker" {
            val result =
                marked(
                    """{"messages":[{"role":"user","content":"Hello"}],"tools":[{"type":"function","function":{"name":"f",
                    "parameters":{"type":"object","properties":{"cache_control":{"type":"string"}}}}}]}""",
                )

            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
        }

        // ---------------------------------------------------------------------------------------------
        // Bodies left as they are
        // ---------------------------------------------------------------------------------------------

        "a body whose messages already carry markers is left as is, so we never mix TTLs nor exceed four breakpoints" {
            val marker = """{"type":"text","text":"old","cache_control":{"type":"ephemeral"}}"""
            assertUntouched(
                """{"messages":[
                {"role":"system","content":"System"},
                {"role":"user","content":[$marker]},
                {"role":"user","content":[$marker]},
                {"role":"user","content":[$marker]},
                {"role":"user","content":"Latest"}]}""",
            )
        }

        "a body that is not a chat completions body is left as is" {
            assertUntouched("""{"model":"text-embedding-3","input":["hello"]}""")
        }

        "a body with no messages is left as is, without auto_cache" {
            assertUntouched("""{"messages":[]}""")
        }

        "a body without any text is left as is, without auto_cache" {
            assertUntouched(
                """{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]},
                {"role":"assistant","content":null,"tool_calls":[]}]}""",
            )
        }

        "a body whose requesty field is not an object is left as is" {
            assertUntouched("""{"messages":[{"role":"user","content":"Hello"}],"requesty":"yes"}""")
        }

        // ---------------------------------------------------------------------------------------------
        // rewriteChatCompletionsBody
        // ---------------------------------------------------------------------------------------------

        val chatBytes = """{"messages":[{"role":"user","content":"Hello"}],"temperature":0.7}""".toByteArray()

        "the raw body of a chat completions call is rewritten, numbers kept as written" {
            val rewritten = RequestyPromptCache.rewriteChatCompletionsBody("/v1/chat/completions", chatBytes)

            val text = String(rewritten)
            mapper.readTree(text).get("requesty").get("auto_cache").asBoolean() shouldBe false
            text.contains("\"temperature\":0.7") shouldBe true
        }

        "the raw body of another endpoint is returned as is" {
            RequestyPromptCache.rewriteChatCompletionsBody("/v1/embeddings", chatBytes) shouldBeSameInstanceAs chatBytes
            RequestyPromptCache.rewriteChatCompletionsBody(null, chatBytes) shouldBeSameInstanceAs chatBytes
        }

        "a raw body that is not JSON, or not an object, is returned as is" {
            val garbage = "not json {".toByteArray()
            val array = "[1,2]".toByteArray()
            val empty = ByteArray(0)

            RequestyPromptCache.rewriteChatCompletionsBody("/v1/chat/completions", garbage) shouldBeSameInstanceAs garbage
            RequestyPromptCache.rewriteChatCompletionsBody("/v1/chat/completions", array) shouldBeSameInstanceAs array
            RequestyPromptCache.rewriteChatCompletionsBody("/v1/chat/completions", empty) shouldBeSameInstanceAs empty
        }

        "a raw body above the size cap is returned as is" {
            val huge = RequestyPromptCacheTestBodies.chatBody(RequestyPromptCache.MAX_REWRITE_BYTES.toInt() + 1)

            RequestyPromptCache.rewriteChatCompletionsBody("/v1/chat/completions", huge) shouldBeSameInstanceAs huge
        }
    })

/** Request bodies shared by the specs of [RequestyPromptCache]. */
internal object RequestyPromptCacheTestBodies {
    /** A valid chat completions body of at least [minSize] bytes. */
    fun chatBody(minSize: Int): ByteArray =
        """{"messages":[{"role":"user","content":"${"a".repeat(minSize)}"}]}""".toByteArray()
}
