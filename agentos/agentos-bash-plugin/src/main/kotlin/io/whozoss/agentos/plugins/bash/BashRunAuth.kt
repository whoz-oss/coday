package io.whozoss.agentos.plugins.bash

import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogging

/**
 * Names of the environment variables through which the secret of the Auth Setting bound to a BASH
 * integration reaches its commands. They are injected into the process environment, never substituted
 * in the command text: the command is logged and may be echoed, the environment is neither.
 */
object BashAuthVariables {
    /**
     * The secret: the OAuth access token, the bearer token, the API key or, for a basic-auth setting, the
     * password (so a command written for a token also works with a forge's username + token setting).
     */
    const val TOKEN = "AGENTOS_AUTH_TOKEN"

    /** Basic-auth settings only. */
    const val USERNAME = "AGENTOS_AUTH_USERNAME"

    /** Basic-auth settings only; same value as [TOKEN]. */
    const val PASSWORD = "AGENTOS_AUTH_PASSWORD"

    /** The credential type name (e.g. `BEARER_TOKEN`), so a script can branch on it. */
    const val TYPE = "AGENTOS_AUTH_TYPE"

    /** The variables whose value is a secret, redacted from the output. The username and the type are not. */
    internal val SECRETS: List<String> = listOf(TOKEN, PASSWORD)
}

/**
 * What the commands of one run get from the Auth Setting bound to the BASH integration, resolved by the first
 * command that runs ([BashRunAuth]). `toString` of every variant is redacted.
 */
sealed interface BashAuthEnvironment {

    /** No credential provider on the run (no Auth Setting bound, or none could be built): no variable is set. */
    data object None : BashAuthEnvironment

    /**
     * The variables to add to the process environment.
     *
     * [redact] is a best-effort defence in depth, not a guarantee: it replaces the literal secret values
     * in text returned to the LLM, but a command is free to encode a value (base64, reversed, split) and
     * the output is truncated before it gets here, which can cut a value in two. The protection that
     * holds is the environment injection itself; do not hand a secret to a command that must not be
     * able to read it.
     */
    data class Variables(val values: Map<String, String>) : BashAuthEnvironment {

        /** Replaces each secret value found in [text] by [REDACTION]; values under [MIN_REDACTED_LENGTH] chars are left alone. */
        fun redact(text: String): String {
            if (text.isEmpty()) return text
            val secrets =
                BashAuthVariables.SECRETS
                    .mapNotNull { values[it] }
                    .filter { it.length >= MIN_REDACTED_LENGTH }
                    .distinct()
                    .sortedByDescending { it.length } // a secret containing another is replaced first
            return secrets.fold(text) { redacted, secret -> redacted.replace(secret, REDACTION) }
        }

        override fun toString(): String = "Variables(${values.keys.sorted().joinToString()}; values redacted)"
    }

    /** An Auth Setting is bound but no usable credential could be resolved; [reason] is safe to surface. */
    data class Missing(val reason: String) : BashAuthEnvironment

    companion object {
        const val REDACTION = "***"

        /** Shorter values would mangle ordinary output ("abc" in "abcdef"). */
        const val MIN_REDACTED_LENGTH = 4

        const val NO_CREDENTIAL = "no credential available for the bound Auth Setting"

        /** A provider is bound; [credential] is what it returned. */
        fun from(credential: Credential?): BashAuthEnvironment {
            if (credential == null) return Missing(NO_CREDENTIAL)
            val type = credential.credentialType
            val values: Map<String, String> =
                when (type) {
                    CredentialType.OAUTH_TOKENS -> token(credential, "accessToken")
                    CredentialType.BEARER_TOKEN -> token(credential, "token")
                    CredentialType.API_KEY -> token(credential, "key")
                    CredentialType.BASIC_AUTH -> basic(credential)
                } ?: return Missing("credential of type $type carries no usable material")
            return Variables(values + (BashAuthVariables.TYPE to type.name))
        }

        private fun token(credential: Credential, key: String): Map<String, String>? =
            material(credential, key)?.let { mapOf(BashAuthVariables.TOKEN to it) }

        private fun basic(credential: Credential): Map<String, String>? {
            val username = material(credential, "username") ?: return null
            val password = material(credential, "password") ?: return null
            return mapOf(
                BashAuthVariables.USERNAME to username,
                BashAuthVariables.PASSWORD to password,
                BashAuthVariables.TOKEN to password,
            )
        }

        private fun material(credential: Credential, key: String): String? =
            credential.data[key]?.takeIf { it.isNotBlank() }
    }
}

/**
 * The authentication of the commands of one run, resolved by the first command that needs it rather than when
 * the tools are built: for an OAuth Auth Setting the service's credential provider may ask the user to
 * authorize and wait for the answer, which must only happen when one of the integration's tools is actually
 * called, never on a run that does not use them.
 *
 * One instance is created per `provideTools` call and shared by all the [BashTool]s of the integration. The
 * environment is resolved once and kept for the rest of the run, [BashAuthEnvironment.Missing] included: a
 * declined or timed-out authorization is not asked again by every later command of the run, the next run asks
 * again (the tools are rebuilt every run). Concurrent commands wait for that single resolution. A resolution
 * that throws is answered as [BashAuthEnvironment.Missing], the detail staying in the logs.
 *
 * While the user is asked to authorize, the call holds one `Dispatchers.IO` thread.
 *
 * @param provider The credential provider of the run, null when no Auth Setting is bound (every command then
 *   runs exactly as without this feature). It is not suspending and may block until the user answers, so it
 *   runs on the IO dispatcher, interrupted when the run is cancelled.
 */
class BashRunAuth(
    private val configName: String?,
    private val provider: CredentialProvider?,
) {
    private val mutex = Mutex()

    @Volatile
    private var resolved: BashAuthEnvironment? = null

    suspend fun environment(): BashAuthEnvironment {
        if (provider == null) return BashAuthEnvironment.None
        return resolved ?: mutex.withLock { resolved ?: resolveOnce(provider).also { resolved = it } }
    }

    private suspend fun resolveOnce(provider: CredentialProvider): BashAuthEnvironment {
        val environment =
            try {
                BashAuthEnvironment.from(runInterruptible(Dispatchers.IO) { provider() })
            } catch (e: CancellationException) {
                // The run's own cancellation propagates; any other one is a failed resolution.
                currentCoroutineContext().ensureActive()
                failed(e)
            } catch (e: Exception) {
                failed(e)
            }
        val line = "BASH integration '$configName': auth=$environment for the rest of the run"
        when (environment) {
            is BashAuthEnvironment.Missing -> logger.warn { "$line (${environment.reason})" }
            else -> logger.info { line }
        }
        return environment
    }

    private fun failed(e: Exception): BashAuthEnvironment {
        logger.error(e) { "BASH integration '$configName': credential resolution failed: ${e.message}" }
        return BashAuthEnvironment.Missing(RESOLUTION_FAILED)
    }

    companion object : KLogging() {
        const val RESOLUTION_FAILED = "unexpected error while resolving the credential, see the service logs"

        /** No Auth Setting bound: the commands run as they always did. */
        val NONE = BashRunAuth(configName = null, provider = null)
    }
}
