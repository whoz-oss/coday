package io.whozoss.agentos.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import java.nio.charset.StandardCharsets

private fun providerError(
    status: HttpStatus,
    body: String,
): WebClientResponseException =
    WebClientResponseException(
        status.value(),
        status.reasonPhrase,
        HttpHeaders.EMPTY,
        body.toByteArray(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8,
    )

private const val ANTHROPIC_BODY =
    """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 213462 tokens > 200000 maximum"}}"""

private const val OPENAI_BODY =
    """{"error":{"message":"This model's maximum context length is 8192 tokens. However, your messages resulted in 9000 tokens. """ +
        """Please reduce the length of the messages.","type":"invalid_request_error","code":"context_length_exceeded"}}"""

private const val GEMINI_BODY =
    """{"error":{"code":400,"message":"The input token count (1196265) exceeds the maximum number of tokens allowed (1048575).","status":"INVALID_ARGUMENT"}}"""

/** Classification of context-window overflows, for both the streaming and the blocking path. */
class ContextWindowExceededUnitSpec :
    StringSpec({
        timeout = 5000

        // -------------------------------------------------------------------
        // Streaming path: WebClientResponseException
        // -------------------------------------------------------------------

        "Anthropic 'prompt is too long' becomes a ContextWindowExceededException with both token counts" {
            val result = providerError(HttpStatus.BAD_REQUEST, ANTHROPIC_BODY).unwrapToProviderAiException()

            result.shouldBeInstanceOf<ContextWindowExceededException>()
            result.promptTokens shouldBe 213_462L
            result.maxTokens shouldBe 200_000L
            result.message shouldContain "Context window exceeded (213462 > 200000 tokens)"
            result.message shouldContain "400"
        }

        "OpenAI context_length_exceeded gives the prompt size (the second number) and the limit (the first)" {
            val result = providerError(HttpStatus.BAD_REQUEST, OPENAI_BODY).unwrapToProviderAiException()

            result.shouldBeInstanceOf<ContextWindowExceededException>()
            result.promptTokens shouldBe 9_000L
            result.maxTokens shouldBe 8_192L
        }

        "Gemini 'exceeds the maximum number of tokens' becomes a ContextWindowExceededException with both token counts" {
            val result = providerError(HttpStatus.BAD_REQUEST, GEMINI_BODY).unwrapToProviderAiException()

            result.shouldBeInstanceOf<ContextWindowExceededException>()
            result.promptTokens shouldBe 1_196_265L
            result.maxTokens shouldBe 1_048_575L
        }

        "the patterns are matched case-insensitively" {
            val result = providerError(HttpStatus.BAD_REQUEST, "PROMPT IS TOO LONG").unwrapToProviderAiException()

            result.shouldBeInstanceOf<ContextWindowExceededException>()
        }

        "an overflow without token counts leaves them null and keeps a clean message" {
            val result =
                providerError(HttpStatus.BAD_REQUEST, """{"error":{"code":"context_length_exceeded"}}""").unwrapToProviderAiException()

            result.shouldBeInstanceOf<ContextWindowExceededException>()
            result.promptTokens shouldBe null
            result.maxTokens shouldBe null
            result.message shouldContain "Context window exceeded:"
        }

        "the provider body never appears in the message of a classified overflow" {
            listOf(ANTHROPIC_BODY, OPENAI_BODY, GEMINI_BODY).forEach { body ->
                val result = providerError(HttpStatus.BAD_REQUEST, body).unwrapToProviderAiException()

                result.shouldBeInstanceOf<ContextWindowExceededException>()
                result.message shouldNotContain "prompt is too long"
                result.message shouldNotContain "maximum context length"
                result.message shouldNotContain "input token count"
                result.message shouldNotContain "invalid_request_error"
            }
        }

        "an overflow wrapped by Reactor is still classified" {
            val wrapped = Exceptions.propagate(providerError(HttpStatus.BAD_REQUEST, ANTHROPIC_BODY))

            wrapped.unwrapToProviderAiException().shouldBeInstanceOf<ContextWindowExceededException>()
        }

        "an unrelated 400 stays a plain NonTransientAiException" {
            val result =
                providerError(HttpStatus.BAD_REQUEST, """{"error":{"message":"tools.9.custom.name: bad pattern"}}""")
                    .unwrapToProviderAiException()

            result.shouldBeInstanceOf<NonTransientAiException>()
            (result is ContextWindowExceededException) shouldBe false
        }

        "a 5xx whose body mentions an overflow is not classified: it stays transient" {
            val result = providerError(HttpStatus.BAD_GATEWAY, ANTHROPIC_BODY).unwrapToProviderAiException()

            result.shouldBeInstanceOf<TransientAiException>()
        }

        "the ContextWindowExceededException is a NonTransientAiException, so existing catch blocks still apply" {
            val result = providerError(HttpStatus.BAD_REQUEST, ANTHROPIC_BODY).unwrapToProviderAiException()

            result.shouldBeInstanceOf<NonTransientAiException>()
        }

        // -------------------------------------------------------------------
        // asContextWindowExceeded: both paths
        // -------------------------------------------------------------------

        "a blocking-call NonTransientAiException carrying the body in its message is classified with a clean message" {
            val original = NonTransientAiException("400 - $ANTHROPIC_BODY")

            val result = original.asContextWindowExceeded()

            result shouldNotBe null
            result!!.promptTokens shouldBe 213_462L
            result.maxTokens shouldBe 200_000L
            result.message shouldNotContain "prompt is too long"
            result.message shouldNotContain "invalid_request_error"
            // The original exception (its message holds the body) is not kept as the cause either.
            result.cause shouldBe null
        }

        "a blocking-call overflow wrapped in other exceptions is found in the cause chain" {
            val wrapped = RuntimeException("outer", IllegalStateException("inner", NonTransientAiException("400 - $OPENAI_BODY")))

            val result = wrapped.asContextWindowExceeded()

            result shouldNotBe null
            result!!.promptTokens shouldBe 9_000L
        }

        "a streaming overflow wrapped by Reactor is classified by asContextWindowExceeded" {
            val wrapped = Exceptions.propagate(providerError(HttpStatus.BAD_REQUEST, GEMINI_BODY))

            wrapped.asContextWindowExceeded()?.maxTokens shouldBe 1_048_575L
        }

        "an already classified exception is returned as is" {
            val classified = ContextWindowExceededException("Context window exceeded", null, 10L, 5L)

            classified.asContextWindowExceeded() shouldBeSameInstanceAs classified
        }

        "a NonTransientAiException that is not an overflow is not classified" {
            NonTransientAiException("400 - {\"error\":\"bad tool name\"}").asContextWindowExceeded() shouldBe null
            RuntimeException("something else").asContextWindowExceeded() shouldBe null
        }

        "a TransientAiException is not classified even when its message mentions an overflow" {
            TransientAiException("503 - $ANTHROPIC_BODY").asContextWindowExceeded() shouldBe null
        }

        "CancellationException is never converted, even with an overflow in its cause chain" {
            CancellationException("cancelled").asContextWindowExceeded() shouldBe null
            CancellationException("cancelled", NonTransientAiException("400 - $ANTHROPIC_BODY")).asContextWindowExceeded() shouldBe null
            RuntimeException("wrapper", CancellationException("cancelled")).asContextWindowExceeded() shouldBe null
        }
    })
