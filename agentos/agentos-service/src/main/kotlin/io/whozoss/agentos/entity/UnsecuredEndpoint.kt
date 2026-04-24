package io.whozoss.agentos.entity

/**
 * Annotation pour marquer les endpoints qui ne doivent pas avoir de vérification de permissions.
 *
 * Cette annotation doit être utilisée avec parcimonie et uniquement pour les endpoints qui
 * ne nécessitent vraiment aucune authentification ou autorisation, comme :
 * - Les endpoints de health check
 * - Les endpoints de login/logout
 * - Les endpoints publics documentés
 *
 * IMPORTANT: L'utilisation de cette annotation doit être justifiée et documentée.
 * La valeur "reason" est obligatoire pour expliquer pourquoi l'endpoint n'est pas sécurisé.
 *
 * Exemple d'utilisation :
 * ```kotlin
 * @UnsecuredEndpoint(reason = "Health check endpoint must be accessible without authentication for monitoring")
 * @GetMapping("/health")
 * fun health(): String = "OK"
 * ```
 *
 * @property reason Explication obligatoire de pourquoi cet endpoint n'a pas de vérification de permissions
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class UnsecuredEndpoint(
    val reason: String
)