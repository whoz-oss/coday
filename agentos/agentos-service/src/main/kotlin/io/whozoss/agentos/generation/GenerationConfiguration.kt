package io.whozoss.agentos.generation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Threading of the generation features ([StructuredLlmClient]).
 *
 * The LLM call of Spring AI is blocking and is not interrupted by coroutine cancellation. Running it on the shared
 * `Dispatchers.IO` would let a burst of generation requests take a large share of that pool, also used by the rest of
 * the service. The generation calls get their own view of it instead, capped at
 * `agentos.generation.max-concurrent-calls` (default [DEFAULT_MAX_CONCURRENT_CALLS]): beyond it, callers wait for a
 * slot rather than take more threads.
 */
@Configuration
class GenerationConfiguration {
    @Bean(GENERATION_DISPATCHER)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun generationDispatcher(
        @Value("\${agentos.generation.max-concurrent-calls:$DEFAULT_MAX_CONCURRENT_CALLS}") maxConcurrentCalls: Int,
    ): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(maxConcurrentCalls)

    companion object {
        /** Qualifier of the dispatcher the generation LLM calls run on. */
        const val GENERATION_DISPATCHER = "generationDispatcher"
        const val DEFAULT_MAX_CONCURRENT_CALLS = 8
        const val DEFAULT_TIMEOUT_SECONDS = 120L
    }
}

/** A generation LLM call that did not answer within `agentos.generation.timeout-seconds`. */
@ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
class GenerationTimeoutException(
    message: String,
) : RuntimeException(message)
