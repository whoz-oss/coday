package io.whozoss.agentos.sdk.auth

import io.whozoss.agentos.sdk.credential.Credential

/** Host-installed connection binding; never construct from tool arguments or integration JSON.
 * The trusted transport checks the final destination and obtains its credential in one operation.
 * In-process plugins remain trusted code, not a sandbox.
 */
interface ConnectionCapability {
    fun authorize(destination: String): Credential?
    fun requireActive()
    fun isGoverned(): Boolean = true
}
