package io.whozoss.agentos.auth

import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.auth.NamespaceRole
import io.whozoss.agentos.security.SecurityConfigProperties
import io.whozoss.agentos.security.SecurityMode
import io.whozoss.agentos.security.SecurityService
import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/**
 * Bootstrap service that runs at application startup.
 *
 * Responsibilities:
 * 1. **Auto-root** (LOCAL mode, FR33): resolve the OS user and grant root status.
 * 2. **Migration** (FR36): assign ADMIN on all existing namespaces to every user
 *    that has no role yet — ensures backward compatibility.
 *
 * The bootstrap is **idempotent**: it checks the current state before acting
 * and can be re-executed without side effects.
 */
@Service
class BootstrapService(
    private val userService: UserService,
    private val roleRepository: RoleRepository,
    private val securityConfigProperties: SecurityConfigProperties,
    private val namespaceService: NamespaceService,
    private val securityService: SecurityService,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        logger.info { "Bootstrap: starting..." }

        val allUsers = userService.findAll()
        val hasRoot = allUsers.any { it.isRoot }

        when {
            hasRoot -> logger.info { "Bootstrap: root user already exists — skipping auto-root" }
            else -> bootstrapRoot()
        }

        migrateExistingUsers()

        logger.info { "Bootstrap: completed" }
    }

    /**
     * Auto-root logic based on security mode.
     *
     * - LOCAL: resolve the OS user, create if absent, grant isRoot=true.
     * - AUTH: log a warning — root must be configured manually.
     */
    private fun bootstrapRoot() {
        when (securityConfigProperties.mode) {
            SecurityMode.LOCAL -> {
                val externalId = securityService.resolveCurrentIdentity()
                val user = userService.resolveOrCreateByExternalId(externalId)
                val userId = user.id.toString()
                roleRepository.setRoot(userId, true)
                logger.info { "Bootstrap: auto-root granted to user $userId (externalId=$externalId) in LOCAL mode" }
            }
            SecurityMode.AUTH -> {
                logger.warn { "Bootstrap: no root user configured — set one manually (AUTH mode)" }
            }
        }
    }

    /**
     * Migration bootstrap: for every existing user, assign ADMIN on every
     * existing namespace where the user has no role yet.
     *
     * This ensures backward compatibility when permissions are activated
     * on a system with existing data (FR36, NFR11, NFR13).
     */
    private fun migrateExistingUsers() {
        val allUsers = userService.findAll()
        val allNamespaces = namespaceService.findAll()

        when {
            allNamespaces.isEmpty() -> {
                logger.info { "Bootstrap: no namespaces found — skipping migration" }
                return
            }
        }

        var migratedCount = 0
        allUsers.forEach { user ->
            val userId = user.id.toString()
            allNamespaces.forEach { namespace ->
                val nsId = namespace.id.toString()
                val existingRole = roleRepository.findNamespaceRole(userId, nsId)
                when (existingRole) {
                    null -> {
                        roleRepository.assignNamespaceRole(userId, nsId, NamespaceRole.ADMIN, "bootstrap")
                        migratedCount++
                        logger.info { "Bootstrap: assigned ADMIN to user $userId on namespace $nsId" }
                    }
                    else -> { /* already has a role — skip */ }
                }
            }
        }

        logger.info { "Bootstrap: migration completed — $migratedCount role assignments created" }
    }

    companion object : KLogging()
}
