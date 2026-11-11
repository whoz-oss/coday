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
 * The rule that names the environment variables through which the secret of the Auth Setting bound to a BASH
 * integration reaches its commands. They are injected into the process environment, never substituted in the
 * command text: the command is logged and may be echoed, the environment is neither.
 *
 * The variable is named after the Auth Setting (`<NAME>`), so that an integration bound to `GITHUB_TOKEN` gives
 * its commands `$GITHUB_TOKEN`:
 * - `<NAME>`: the secret, i.e. the OAuth access token, bearer token, API key or, for a basic-auth setting, the
 *   password (so a command written for a token also works with a forge's username + token setting);
 * - `<NAME>_USERNAME`: the username, basic-auth settings only.
 */
object BashAuthVariables {
    /** Appended to the Auth Setting name for the username of a basic-auth setting. */
    const val USERNAME_SUFFIX = "_USERNAME"

    private val VALID_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /**
     * Names an Auth Setting cannot have for its variable, because setting them would change how the shell or
     * the process behaves instead of just carrying a secret: `PATH` would break every command, `BASH_ENV` (or
     * `ENV`) would make a shell source a file named by the secret, `IFS`, `PS4`, `PROMPT_COMMAND`, `SHELLOPTS`
     * and `BASHOPTS` alter how the shell parses or runs, `HOME`, `USER`, `SHELL`, `PWD`, `OLDPWD`, `CDPATH`,
     * `TMPDIR`, `LANG` and `TERM` redirect or reconfigure what commands find and print. Compared case-sensitively
     * and exactly; the prefixes in [RESERVED_PREFIXES] are reserved too.
     */
    val RESERVED_NAMES: Set<String> =
        setOf(
            "PATH", "HOME", "USER", "SHELL", "PWD", "OLDPWD", "IFS", "ENV", "BASH_ENV", "BASHOPTS", "SHELLOPTS",
            "CDPATH", "PS4", "PROMPT_COMMAND", "TMPDIR", "LANG", "TERM",
        )

    /** Prefixes of reserved families: the dynamic loader's (`LD_`, `DYLD_`) and exported bash functions. */
    val RESERVED_PREFIXES: List<String> = listOf("LD_", "DYLD_", "BASH_FUNC_")

    /** The variable holding the username of a basic-auth setting named [name]. */
    fun usernameOf(name: String): String = name + USERNAME_SUFFIX

    /**
     * Why [name] cannot be used as the variable of an Auth Setting, or null when it can. The name of an Auth
     * Setting is not a secret, so the reason may quote it.
     */
    fun problemWith(name: String?): String? =
        when {
            name.isNullOrBlank() -> "No Auth Setting name was provided by the service, so the command cannot get its credential."
            !VALID_NAME.matches(name) ->
                "Auth Setting '$name' cannot be used: its name is not a valid environment variable name " +
                    "(letters, digits and underscores, not starting with a digit). Rename the Auth Setting."
            name in RESERVED_NAMES || RESERVED_PREFIXES.any { name.startsWith(it) } ->
                "Auth Setting '$name' cannot be used: its name is reserved. Rename the Auth Setting."
            else -> null
        }
}

/**
 * What the commands of one run get from the Auth Setting bound to the BASH integration, resolved by the first
 * command that runs ([BashRunAuth]). `toString` of every variant is redacted.
 */
sealed interface BashAuthEnvironment {

    /** No credential provider on the run (no Auth Setting bound, or none could be built): no variable is set. */
    data object None : BashAuthEnvironment

    /**
     * The variables to add to the process environment; [secretName] is the one holding the secret.
     *
     * [redact] is a best-effort defence in depth, not a guarantee: it replaces the literal secret value
     * in text returned to the LLM, but a command is free to encode a value (base64, reversed, split) and
     * the output is truncated before it gets here, which can cut a value in two. The protection that
     * holds is the environment injection itself; do not hand a secret to a command that must not be
     * able to read it.
     */
    data class Variables(val values: Map<String, String>, val secretName: String) : BashAuthEnvironment {

        /** Replaces the secret value found in [text] by [REDACTION]; a value under [MIN_REDACTED_LENGTH] chars is left alone. */
        fun redact(text: String): String {
            val secret = values[secretName]
            if (text.isEmpty() || secret == null || secret.length < MIN_REDACTED_LENGTH) return text
            return text.replace(secret, REDACTION)
        }

        override fun toString(): String = "Variables(${values.keys.sorted().joinToString()}; values redacted)"
    }

    /** An Auth Setting is bound but no usable credential could be resolved; [reason] is safe to surface. */
    data class Missing(val reason: String) : BashAuthEnvironment

    companion object {
        const val REDACTION = "***"

        /** Shorter values would mangle ordinary output ("abc" in "abcdef"). */
        const val MIN_REDACTED_LENGTH = 4

        /** The provider returned no credential for the Auth Setting [name]. */
        fun noCredential(name: String): String =
            "Auth Setting '$name' gave no credential: its secret is empty, " +
                "or it is an OAuth setting whose authorization was not completed."

        /** The credential of type [type] has no usable secret for the Auth Setting [name]. */
        fun noUsableSecret(name: String, type: CredentialType): String =
            "Auth Setting '$name' ($type) carries no usable secret."

        /** A provider is bound under [name] (already validated); [credential] is what it returned. */
        fun from(name: String, credential: Credential?): BashAuthEnvironment {
            if (credential == null) return Missing(noCredential(name))
            val type = credential.credentialType
            val values: Map<String, String> =
                when (type) {
                    CredentialType.OAUTH_TOKENS -> token(name, credential, "accessToken")
                    CredentialType.BEARER_TOKEN -> token(name, credential, "token")
                    CredentialType.API_KEY -> token(name, credential, "key")
                    CredentialType.BASIC_AUTH -> basic(name, credential)
                } ?: return Missing(noUsableSecret(name, type))
            return Variables(values, secretName = name)
        }

        private fun token(name: String, credential: Credential, key: String): Map<String, String>? =
            material(credential, key)?.let { mapOf(name to it) }

        private fun basic(name: String, credential: Credential): Map<String, String>? {
            val username = material(credential, "username") ?: return null
            val password = material(credential, "password") ?: return null
            return mapOf(name to password, BashAuthVariables.usernameOf(name) to username)
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
 * The variable is named after the Auth Setting ([BashAuthVariables]). That name is known up front, so it is
 * checked before the provider is invoked: a missing, invalid or reserved name answers
 * [BashAuthEnvironment.Missing] without ever asking the user to authorize.
 *
 * While the user is asked to authorize, the call holds one `Dispatchers.IO` thread.
 *
 * @param authSettingName The name of the bound Auth Setting, which names the variable carrying the secret.
 * @param provider The credential provider of the run, null when no Auth Setting is bound (every command then
 *   runs exactly as without this feature). It is not suspending and may block until the user answers, so it
 *   runs on the IO dispatcher, interrupted when the run is cancelled.
 */
class BashRunAuth(
    private val configName: String?,
    private val authSettingName: String?,
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
        val name = authSettingName
        val environment =
            when (val problem = BashAuthVariables.problemWith(name)) {
                null -> resolveCredential(name!!, provider)
                else -> BashAuthEnvironment.Missing(problem)
            }
        val line = "BASH integration '$configName': auth=$environment for the rest of the run"
        when (environment) {
            is BashAuthEnvironment.Missing -> logger.warn { "$line (${environment.reason})" }
            else -> logger.info { line }
        }
        return environment
    }

    private suspend fun resolveCredential(name: String, provider: CredentialProvider): BashAuthEnvironment =
        try {
            BashAuthEnvironment.from(name, runInterruptible(Dispatchers.IO) { provider() })
        } catch (e: CancellationException) {
            // The run's own cancellation propagates; any other one is a failed resolution.
            currentCoroutineContext().ensureActive()
            failed(name, e)
        } catch (e: Exception) {
            failed(name, e)
        }

    /** The exception is logged, never put in the reason: it can carry ids or other data. */
    private fun failed(name: String, e: Exception): BashAuthEnvironment {
        logger.error(e) { "BASH integration '$configName': credential resolution failed: ${e.message}" }
        return BashAuthEnvironment.Missing(resolutionFailed(name))
    }

    companion object : KLogging() {
        /** The provider threw while resolving the Auth Setting [name]. */
        fun resolutionFailed(name: String): String =
            "Auth Setting '$name' could not be resolved for this run. Check that a setting with exactly " +
                "this name (case-sensitive) exists for this namespace, for you, or at platform level. " +
                "The service log has the detail."

        /** No Auth Setting bound: the commands run as they always did. */
        val NONE = BashRunAuth(configName = null, authSettingName = null, provider = null)
    }
}
