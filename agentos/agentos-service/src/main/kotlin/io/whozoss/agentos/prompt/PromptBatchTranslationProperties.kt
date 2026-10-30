package io.whozoss.agentos.prompt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for [PromptServiceImpl.translateBatch].
 *
 * Bound from the `agentos.prompt.batch-translation` prefix in application.yml.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_PROMPT_BATCH_TRANSLATION_PARALLELISM_LIMIT
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   prompt:
 *     batch-translation:
 *       parallelism-limit: 10
 * ```
 */
@ConfigurationProperties(prefix = "agentos.prompt.batch-translation")
data class PromptBatchTranslationProperties(
    /**
     * Maximum number of prompts translated concurrently in a single [PromptServiceImpl.translateBatch]
     * call. Each concurrent slot may issue a Neo4j permission check and an LLM HTTP call, so this
     * value caps the outbound fan-out against both systems.
     *
     * Defaults to 30, which saturates a typical Dispatchers.IO pool (64 threads) without starving
     * other concurrent requests.
     */
    val parallelismLimit: Int = 30,
)
