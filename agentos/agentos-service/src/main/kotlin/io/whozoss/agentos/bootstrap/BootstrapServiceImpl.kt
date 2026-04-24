package io.whozoss.agentos.bootstrap

import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Bootstrap service implementation that runs at application startup.
 *
 * Implements ApplicationRunner to execute automatically after the Spring context
 * starts but before the application begins accepting requests.
 *
 * Its primary role is to ensure the system has at least one super-admin.
 * The first user to connect is automatically promoted to super-admin,
 * which is handled by UserService during user creation.
 *
 * @property userService Service for managing users
 */
@Service
@ConditionalOnProperty(
    prefix = "agentos.bootstrap",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class BootstrapServiceImpl(
    private val userService: UserService
) : BootstrapService, ApplicationRunner {

    companion object : KLogging()

    /**
     * Called automatically by Spring Boot at startup.
     * Delegates to bootstrap() for the actual execution.
     */
    override fun run(args: ApplicationArguments) {
        bootstrap()
    }

    /**
     * Runs the bootstrap operations.
     *
     * Checks if users already exist in the system.
     * If no users exist, the next user created will be
     * automatically promoted to super-admin by UserService.
     */
    override fun bootstrap() {
        logger.info { "[Bootstrap] Starting bootstrap process..." }

        if (isBootstrapped()) {
            logger.info { "[Bootstrap] System already bootstrapped - users exist. Skipping bootstrap." }
            return
        }

        logger.info { "[Bootstrap] No users found in system. First user will be auto-promoted to super-admin." }
        logger.info { "[Bootstrap] Bootstrap process completed." }
    }

    /**
     * Checks if the system has already been initialized.
     *
     * @return true if at least one user exists, false otherwise
     */
    override fun isBootstrapped(): Boolean {
        val userCount = userService.count()
        logger.debug { "[Bootstrap] Current user count: $userCount" }
        return userCount > 0
    }
}
