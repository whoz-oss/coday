package io.whozoss.agentos.entity

/**
 * Explicit scope discriminator for the 4-tier overlay model shared across
 * [io.whozoss.agentos.prompt.Prompt], [io.whozoss.agentos.integrationConfig.IntegrationConfig],
 * and [io.whozoss.agentos.aiProvider.AiProvider].
 *
 * | Value          | namespaceId | userId   |
 * |----------------|-------------|----------|
 * | PLATFORM       | null        | null     |
 * | NAMESPACE      | non-null    | null     |
 * | USER           | null        | callerId |
 * | USER_NAMESPACE | non-null    | callerId |
 */
enum class OverlayScope {
    PLATFORM,
    NAMESPACE,
    USER,
    USER_NAMESPACE,
}
