package io.whozoss.agentos.bootstrap

import io.whozoss.agentos.user.UserService
import mu.KLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Implémentation du service de bootstrap qui s'exécute au démarrage de l'application.
 *
 * Ce service implémente ApplicationRunner pour s'exécuter automatiquement après
 * le démarrage du contexte Spring mais avant que l'application n'accepte des requêtes.
 *
 * Le principal rôle est de s'assurer que le système a au moins un super-admin.
 * Le premier utilisateur qui se connecte devient automatiquement super-admin,
 * ce qui est géré par UserService lors de la création.
 *
 * @property userService Service pour gérer les utilisateurs
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
     * Méthode appelée automatiquement par Spring Boot au démarrage.
     * Délègue à la méthode bootstrap() pour l'exécution réelle.
     */
    override fun run(args: ApplicationArguments) {
        bootstrap()
    }

    /**
     * Exécute les opérations de bootstrap.
     *
     * Vérifie si des utilisateurs existent déjà dans le système.
     * Si aucun utilisateur n'existe, le prochain utilisateur créé
     * sera automatiquement promu super-admin par UserService.
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
     * Vérifie si le système a déjà été initialisé.
     *
     * @return true si au moins un utilisateur existe, false sinon
     */
    override fun isBootstrapped(): Boolean {
        val userCount = userService.count()
        logger.debug { "[Bootstrap] Current user count: $userCount" }
        return userCount > 0
    }
}