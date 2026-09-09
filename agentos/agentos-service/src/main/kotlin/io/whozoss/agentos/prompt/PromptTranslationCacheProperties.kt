package io.whozoss.agentos.prompt

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration properties for the in-process LLM translation cache in [PromptTranslationServiceImpl].
 *
 * Bound from the `agentos.prompt.translation.cache` prefix in application.yml.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_PROMPT_TRANSLATION_CACHE_MAX_SIZE
 * - AGENTOS_PROMPT_TRANSLATION_CACHE_TTL
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   prompt:
 *     translation:
 *       cache:
 *         max-size: 1000
 *         ttl: P30D
 * ```
 */
@ConfigurationProperties(prefix = "agentos.prompt.translation.cache")
data class PromptTranslationCacheProperties(
    /**
     * Maximum number of entries the translation cache can hold.
     * Older or less-frequently-used entries are evicted once this limit is reached.
     * Defaults to 1 000, which covers a large prompt library across several languages.
     */
    val maxSize: Long = 1_000L,
    /**
     * Time-to-live for each cache entry.
     * Translations are stable text that only becomes stale when an author edits the source prompt
     * (at which point [PromptServiceImpl] clears the persisted translation maps).
     * The TTL is therefore a safety net for entries that survive a service restart or a model change.
     * Defaults to 30 days.
     */
    val ttl: Duration = Duration.ofDays(30),
)
