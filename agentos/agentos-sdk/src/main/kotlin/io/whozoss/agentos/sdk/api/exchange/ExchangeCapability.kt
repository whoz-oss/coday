package io.whozoss.agentos.sdk.api.exchange

/**
 * Capability an actor (agent or user) has over an exchange scope.
 *
 * Ordered from least to most permissive so callers may compare ordinals.
 */
enum class ExchangeCapability {
    /** No access. */
    NONE,

    /** Read and list only. */
    READ,

    /** Read, list, write and delete. */
    READ_WRITE,
}
