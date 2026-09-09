package io.whozoss.agentos.sdk.api.exchange

/**
 * Scope of an exchange file: case-private or namespace-shared.
 */
enum class ExchangeScope {
    /** Files private to a single case (`<mountRoot>/<namespaceId>/cases/<caseId>`). */
    CASE,

    /** Files shared across a namespace (`<mountRoot>/<namespaceId>/shared`). */
    NAMESPACE,
}
