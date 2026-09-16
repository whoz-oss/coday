package io.whozoss.agentos.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import java.nio.charset.StandardCharsets

/**
 * Builds a [WebClientResponseException] with a JSON body.
 * Uses the base constructor (statusCode: Int, statusText: String, headers, body, charset)
 * which is stable across Spring 6.x versions and avoids the HttpRequest type mismatch
 * introduced in later Spring 6 releases for the `create()` factory method.
 */
private fun webClientException(
    status: HttpStatus,
    body: String = "",
): WebClientResponseException =
    WebClientResponseException(
        status.value(),
        status.reasonPhrase,
        org.springframework.http.HttpHeaders.EMPTY,
        body.toByteArray(StandardCharsets.UTF_8),
        StandardCharsets.UTF_8,
    )

/**
 * Wraps a throwable the same way Reactor does via [Exceptions.propagate].
 * This simulates what arrives at the agent catch block from a streaming Flux.
 */
private fun reactorWrap(cause: Throwable): Throwable = Exceptions.propagate(cause)

class WebClientExceptionUtilsUnitSpec : StringSpec({

    // -----------------------------------------------------------------------
    // 4xx -- naked WebClientResponseException
    // -----------------------------------------------------------------------

    "4xx bare exception becomes NonTransientAiException without body in message" {
        val body = """{"error":{"message":"tools.9.custom.name: String should match pattern"}}"""
        val ex = webClientException(HttpStatus.BAD_REQUEST, body)

        val result = ex.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
        result.message shouldNotContain body
    }

    "4xx bare exception - message also contains status code" {
        val ex = webClientException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid")

        val result = ex.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
        result.message shouldContain "422"
    }

    // -----------------------------------------------------------------------
    // 4xx -- Reactor-wrapped (the real production path)
    // -----------------------------------------------------------------------

    "4xx wrapped by Reactor becomes NonTransientAiException" {
        val body = """{"error":{"type":"invalid_request_error","message":"bad tool name"}}"""
        val raw = webClientException(HttpStatus.BAD_REQUEST, body)
        val wrapped = reactorWrap(raw)

        val result = wrapped.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
        result.message shouldNotContain body
    }

    "4xx doubly-wrapped cause chain depth 2 is still found" {
        val body = "bad request details"
        val raw = webClientException(HttpStatus.BAD_REQUEST, body)
        val wrapped = RuntimeException("outer", RuntimeException("inner", raw))

        val result = wrapped.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
        result.message shouldNotContain body
    }

    // -----------------------------------------------------------------------
    // 5xx -- becomes TransientAiException
    // -----------------------------------------------------------------------

    "5xx bare exception becomes TransientAiException not NonTransientAiException" {
        val body = """{"error":"internal server error"}"""
        val ex = webClientException(HttpStatus.INTERNAL_SERVER_ERROR, body)

        val result = ex.unwrapToProviderAiException()

        result.shouldBeInstanceOf<TransientAiException>()
        result.message shouldNotContain body
    }

    "429 Too Many Requests is classified as NonTransientAiException by current implementation" {
        // 429 is a 4xx so HttpStatus.is4xxClientError() returns true.
        // This test documents the actual behaviour -- reclassifying 429 as transient
        // is a separate concern that should be addressed explicitly if needed.
        val ex = webClientException(HttpStatus.TOO_MANY_REQUESTS, "rate limit exceeded")

        val result = ex.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
    }

    "503 Service Unavailable wrapped by Reactor becomes TransientAiException" {
        val raw = webClientException(HttpStatus.SERVICE_UNAVAILABLE, "provider down")
        val wrapped = reactorWrap(raw)

        val result = wrapped.unwrapToProviderAiException()

        result.shouldBeInstanceOf<TransientAiException>()
    }

    // -----------------------------------------------------------------------
    // Non-HTTP exceptions -- must return null
    // -----------------------------------------------------------------------

    "arbitrary RuntimeException returns null" {
        val ex = RuntimeException("something else")

        ex.unwrapToProviderAiException() shouldBe null
    }

    "NullPointerException returns null" {
        val ex = NullPointerException("npe")

        ex.unwrapToProviderAiException() shouldBe null
    }

    // -----------------------------------------------------------------------
    // CancellationException -- must never be converted
    // -----------------------------------------------------------------------

    "CancellationException returns null and is never converted" {
        val ex = CancellationException("coroutine cancelled")

        ex.unwrapToProviderAiException() shouldBe null
    }

    "CancellationException in cause chain returns null" {
        // Ensures the cause-walk loop does not convert a CancellationException
        // found buried in the chain.
        val cancel = CancellationException("cancelled")
        val wrapped = RuntimeException("reactor-like wrapper", cancel)

        wrapped.unwrapToProviderAiException() shouldBe null
    }

    // -----------------------------------------------------------------------
    // Response body confidentiality
    // -----------------------------------------------------------------------

    "long response body is not exposed in propagated message" {
        val longBody = "sensitive".repeat(625)
        val ex = webClientException(HttpStatus.BAD_REQUEST, longBody)

        val result = ex.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
        result.message shouldNotContain "sensitive"
        result.message shouldNotContain "[truncated]"
    }

    "empty response body does not add a diagnostic placeholder to propagated message" {
        val ex = webClientException(HttpStatus.BAD_REQUEST, "")

        val result = ex.unwrapToProviderAiException()

        result.shouldBeInstanceOf<NonTransientAiException>()
        result.message shouldNotContain "<empty body>"
    }
})
