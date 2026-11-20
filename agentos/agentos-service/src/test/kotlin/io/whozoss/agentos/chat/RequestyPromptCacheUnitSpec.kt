package io.whozoss.agentos.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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

        // ---------------------------------------------------------------------------------------------
        // isRequesty
        // ---------------------------------------------------------------------------------------------

        "isRequesty matches the Requesty router and its subdomains only" {
            RequestyPromptCache.isRequesty("https://router.eu.requesty.ai") shouldBe true
            RequestyPromptCache.isRequesty("https://router.requesty.ai/v1") shouldBe true
            RequestyPromptCache.isRequesty("https://requesty.ai") shouldBe true
            RequestyPromptCache.isRequesty("HTTPS://ROUTER.REQUESTY.AI") shouldBe true
            RequestyPromptCache.isRequesty("https://api.openai.com") shouldBe false
            RequestyPromptCache.isRequesty("https://notrequesty.ai") shouldBe false
            RequestyPromptCache.isRequesty("https://requesty.ai.evil.com") shouldBe false
            RequestyPromptCache.isRequesty("http://127.0.0.1:8080") shouldBe false
            RequestyPromptCache.isRequesty("not a url") shouldBe false
            RequestyPromptCache.isRequesty("") shouldBe false
            RequestyPromptCache.isRequesty(null) shouldBe false
        }

        // ---------------------------------------------------------------------------------------------
        // withOneHourCacheControl
        // ---------------------------------------------------------------------------------------------

        "a string system message and the last user message get a one-hour marker, and auto caching is turned off" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"model":"anthropic/claude-sonnet-4-5","messages":[
                        {"role":"system","content":"You are helpful."},
                        {"role":"user","content":"Hello"}],"temperature":0.7}""",
                    ),
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
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"messages":[
                        {"role":"system","content":[{"type":"text","text":"Part one"},{"type":"text","text":"Part two"}]},
                        {"role":"user","content":"Hello"}]}""",
                    ),
                )

            val parts = result.message(0).get("content")
            parts.get(0).has("cache_control") shouldBe false
            parts.get(1).get("cache_control") shouldBeJson oneHour
        }

        "only the last of several system messages is marked" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"messages":[
                        {"role":"system","content":"Agent prompt"},
                        {"role":"system","content":"Namespace context"},
                        {"role":"user","content":"Hello"}]}""",
                    ),
                )

            result.message(0).get("content").isTextual shouldBe true
            result.message(1).get("content").get(0).get("cache_control") shouldBeJson oneHour
            result.cacheControlCount() shouldBe 2
        }

        "a system message that is also the last message with text gets a single marker" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body("""{"messages":[{"role":"system","content":"Only a system prompt"}]}"""),
                )

            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
            result.cacheControlCount() shouldBe 1
        }

        "a last tool message is the one marked after the system message" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"messages":[
                        {"role":"system","content":"System"},
                        {"role":"user","content":"Weather in Paris?"},
                        {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{}"}}]},
                        {"role":"tool","tool_call_id":"call_1","content":"sunny"}]}""",
                    ),
                )

            result.message(3).get("content") shouldBeJson
                json("""[{"type":"text","text":"sunny","cache_control":{"type":"ephemeral","ttl":"1h"}}]""")
            result.message(3).get("tool_call_id").asText() shouldBe "call_1"
            result.message(1).get("content").isTextual shouldBe true
            result.cacheControlCount() shouldBe 2
        }

        "a user message with media gets the marker on its last text part, the image is left alone" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"messages":[{"role":"user","content":[
                        {"type":"text","text":"What is on this picture?"},
                        {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]}""",
                    ),
                )

            val parts = result.message(0).get("content")
            parts.get(0).get("cache_control") shouldBeJson oneHour
            parts.get(1) shouldBeJson json("""{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}""")
        }

        "an assistant message that only carries tool calls is never touched" {
            val assistant =
                """{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]}"""
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body("""{"messages":[{"role":"user","content":"Go"},$assistant]}"""),
                )

            result.message(1) shouldBeJson json(assistant)
            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
        }

        "messages with empty text are skipped" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body("""{"messages":[{"role":"user","content":"Hello"},{"role":"user","content":""}]}"""),
                )

            result.message(1).get("content").asText() shouldBe ""
            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
        }

        "applying it twice changes nothing" {
            val original =
                body(
                    """{"messages":[
                    {"role":"system","content":"System"},
                    {"role":"user","content":[{"type":"text","text":"Hi"},{"type":"image_url","image_url":{"url":"x"}}]}],
                    "requesty":{"tags":["a"]}}""",
                )
            val once = RequestyPromptCache.withOneHourCacheControl(original)
            val twice = RequestyPromptCache.withOneHourCacheControl(once)

            twice shouldBeJson once
            twice.cacheControlCount() shouldBe 2
        }

        "the input body is not modified" {
            val original = body("""{"messages":[{"role":"user","content":"Hello"}]}""")
            val snapshot = original.deepCopy()

            RequestyPromptCache.withOneHourCacheControl(original)

            original shouldBeJson snapshot
        }

        "existing requesty fields are kept, auto_cache is forced to false" {
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"messages":[{"role":"user","content":"Hello"}],
                        "requesty":{"auto_cache":true,"tags":["agentos"],"trace_id":"t-1"}}""",
                    ),
                )

            result.get("requesty") shouldBeJson json("""{"auto_cache":false,"tags":["agentos"],"trace_id":"t-1"}""")
        }

        "never more than four breakpoints in the body" {
            val marked = """{"type":"text","text":"old","cache_control":{"type":"ephemeral","ttl":"1h"}}"""
            val result =
                RequestyPromptCache.withOneHourCacheControl(
                    body(
                        """{"messages":[
                        {"role":"system","content":"System"},
                        {"role":"user","content":[$marked]},
                        {"role":"user","content":[$marked]},
                        {"role":"user","content":[$marked]},
                        {"role":"user","content":"Latest"}]}""",
                    ),
                )

            result.cacheControlCount() shouldBe RequestyPromptCache.MAX_BREAKPOINTS
            // The system marker comes first and takes the last free slot.
            result.message(0).get("content").get(0).get("cache_control") shouldBeJson oneHour
            result.message(4).get("content").isTextual shouldBe true
        }

        "a body that is not a chat completions body is returned untouched" {
            val embeddings = body("""{"model":"text-embedding-3","input":["hello"]}""")

            RequestyPromptCache.withOneHourCacheControl(embeddings) shouldBeSameInstanceAs embeddings
            embeddings.has("requesty") shouldBe false
        }

        "a body with markers it did not set is returned untouched" {
            val foreign =
                body(
                    """{"messages":[{"role":"user","content":[
                    {"type":"text","text":"Hello","cache_control":{"type":"ephemeral"}}]}]}""",
                )

            RequestyPromptCache.withOneHourCacheControl(foreign) shouldBeSameInstanceAs foreign
        }

        "a body whose requesty field is not an object is returned untouched" {
            val odd = body("""{"messages":[{"role":"user","content":"Hello"}],"requesty":"yes"}""")

            RequestyPromptCache.withOneHourCacheControl(odd) shouldBeSameInstanceAs odd
        }

        // ---------------------------------------------------------------------------------------------
        // rewriteChatCompletionsBody
        // ---------------------------------------------------------------------------------------------

        val chatBytes = """{"messages":[{"role":"user","content":"Hello"}],"temperature":0.7}""".toByteArray()

        "the raw body of a chat completions call is rewritten, numbers kept as written" {
            val rewritten = RequestyPromptCache.rewriteChatCompletionsBody("/v1/chat/completions", chatBytes)

            val text = String(rewritten)
            mapper.readTree(text).get("requesty").get("auto_cache").asBoolean() shouldBe false
            (text.contains("\"temperature\":0.7")) shouldBe true
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
    })
