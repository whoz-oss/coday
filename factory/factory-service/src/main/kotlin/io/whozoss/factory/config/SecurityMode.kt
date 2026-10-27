package io.whozoss.factory.config

/**
 * Identity resolution mode of the Factory HTTP API, bound from `factory.security.mode`
 * (environment variable `FACTORY_SECURITY_MODE`, default `local`).
 *
 * Mirrors `io.whozoss.agentos.security.SecurityMode` of `agentos-service`. The two
 * builds are separate, so the contract is duplicated, not imported: keep both in sync.
 */
enum class SecurityMode {
    /** Single-user: every caller is the OS user running Factory; headers and bearers are ignored. */
    LOCAL,

    /** Identity comes from a Factory JWT or from the headers set by an authenticating upstream proxy. */
    AUTH,
}
