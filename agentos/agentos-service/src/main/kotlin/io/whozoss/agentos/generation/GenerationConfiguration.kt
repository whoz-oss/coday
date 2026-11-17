package io.whozoss.agentos.generation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Settings of the generation LLM calls ([StructuredLlmClient]), bound from `agentos.generation`.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - `AGENTOS_GENERATION_MAX_CONCURRENT_CALLS` (default 8)
 * - `AGENTOS_GENERATION_TIMEOUT_SECONDS` (default 120)
 *
 * @param maxConcurrentCalls Cap on the generation calls running at once: beyond it, callers wait for a slot.
 * @param timeoutSeconds Bound on one call: past it, the request fails with [GenerationTimeoutException] (504).
 */
@ConfigurationProperties(prefix = "agentos.generation")
data class GenerationConfigProperties(
    val maxConcurrentCalls: Int = 8,
    val timeoutSeconds: Long = 120,
) {
    init {
        require(maxConcurrentCalls > 0) { "agentos.generation.max-concurrent-calls must be positive" }
        require(timeoutSeconds > 0) { "agentos.generation.timeout-seconds must be positive" }
    }
}

/**
 * Threading of the generation features ([StructuredLlmClient]).
 *
 * The LLM call of Spring AI is blocking and is not interrupted by coroutine cancellation. Running it on the shared
 * `Dispatchers.IO` would let a burst of generation requests take a large share of that pool, also used by the rest of
 * the service. The generation calls get their own view of it instead, capped at
 * [GenerationConfigProperties.maxConcurrentCalls]: beyond it, callers wait for a slot rather than take more threads.
 */
@Configuration
@EnableConfigurationProperties(GenerationConfigProperties::class)
class GenerationConfiguration {
    @Bean(GENERATION_DISPATCHER)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun generationDispatcher(properties: GenerationConfigProperties): CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(properties.maxConcurrentCalls)

    companion object {
        /** Qualifier of the dispatcher the generation LLM calls run on. */
        const val GENERATION_DISPATCHER = "generationDispatcher"
    }
}

/** A generation LLM call that did not answer within [GenerationConfigProperties.timeoutSeconds]. */
@ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
class GenerationTimeoutException(
    message: String,
) : RuntimeException(message)
