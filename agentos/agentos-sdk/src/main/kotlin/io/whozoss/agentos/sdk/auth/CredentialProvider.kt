package io.whozoss.agentos.sdk.auth

import io.whozoss.agentos.sdk.credential.Credential

/**
 * A pre-scoped credential supplier.
 *
 * Implementations capture the resolved (namespace, user, authSettingName) at construction
 * time so that plugin code never needs to handle identity resolution or know which
 * AuthSetting it is bound to.
 *
 * Returns null when the user has not yet authenticated for the bound AuthSetting.
 */
typealias CredentialProvider = () -> Credential?
