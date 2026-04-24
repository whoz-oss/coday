package io.whozoss.agentos.bootstrap

/**
 * Service for application startup initialization operations.
 *
 * Defines bootstrap operations that must run once at application startup,
 * primarily to ensure a super-admin exists in the system.
 *
 * The implementation must be idempotent — it can be called multiple times
 * without undesirable side effects.
 */
interface BootstrapService {
    /**
     * Runs the bootstrap operations.
     *
     * Called automatically at application startup. Checks if users already exist
     * and configures the system accordingly.
     *
     * Typical operations include:
     * - Checking if the system already has users
     * - Ensuring the first user becomes super-admin
     * - Initializing other system resources if necessary
     */
    fun bootstrap()

    /**
     * Checks if bootstrap has already been performed.
     *
     * @return true if the system already has at least one user, false otherwise
     */
    fun isBootstrapped(): Boolean
}
