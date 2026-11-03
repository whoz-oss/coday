package io.whozoss.agentos.user

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Bound from `agentos.personal-namespace` (env `AGENTOS_PERSONAL_NAMESPACE_ENABLED`).
 *
 * When [enabled] (the default), [PersonalNamespaceProvisioner] gives every user a private namespace.
 */
@ConfigurationProperties(prefix = "agentos.personal-namespace")
data class PersonalNamespaceProperties(
    val enabled: Boolean = true,
)
