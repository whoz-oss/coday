package io.whozoss.agentos.bootstrap

/**
 * Service pour les opérations d'initialisation au démarrage de l'application.
 *
 * Cette interface définit les opérations de bootstrap qui doivent être exécutées
 * une seule fois au démarrage de l'application, notamment pour s'assurer qu'un
 * super-admin existe dans le système.
 *
 * L'implémentation doit être idempotente - elle peut être appelée plusieurs fois
 * sans effets secondaires indésirables.
 */
interface BootstrapService {
    /**
     * Exécute les opérations de bootstrap.
     *
     * Cette méthode est appelée automatiquement au démarrage de l'application.
     * Elle vérifie si des utilisateurs existent déjà et configure le système
     * en conséquence.
     *
     * Les opérations typiques incluent :
     * - Vérifier si le système a déjà des utilisateurs
     * - S'assurer que le premier utilisateur devient super-admin
     * - Initialiser d'autres ressources système si nécessaire
     */
    fun bootstrap()

    /**
     * Vérifie si le bootstrap a déjà été effectué.
     *
     * @return true si le système a déjà au moins un utilisateur, false sinon
     */
    fun isBootstrapped(): Boolean
}