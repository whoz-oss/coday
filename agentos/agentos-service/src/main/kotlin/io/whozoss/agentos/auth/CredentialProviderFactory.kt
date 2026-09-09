package io.whozoss.agentos.auth

import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.AuthType
import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.credential.Credential
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Builds the run-scoped [CredentialProvider] that a plugin receives through
 * `ToolContext.credentialProvider` for an `IntegrationConfig.authSettingName`.
 *
 * Shared by the agent run (`AgentServiceImpl` hands the result to `ToolResolverService`) and by
 * the integration-config tool preview, which resolves tools for the current user without a case.
 *
 * Resolution, once the returned provider is invoked by the plugin:
 * - the `AuthSetting` is resolved by name through the 4-tier overlay for `(namespaceId, userId)`;
 * - OAuth types go through [OAuthFlowService] (existing token -> refresh -> interactive flow) when
 *   the run has a [caseId][forRun] and an event sink; without them they fall back to the direct
 *   per-user `Credential` lookup and are never synthesised;
 * - static types (`API_KEY`, `BEARER_TOKEN`, `BASIC_AUTH`): the per-user `Credential` row wins,
 *   otherwise [StaticCredentialFactory] synthesises the credential in memory from the resolved
 *   `AuthSetting`. Nothing is persisted on that path.
 *
 * Secret values are never logged: log lines carry auth-setting names, types and ids only.
 */
@Service
class CredentialProviderFactory(
    private val authServiceFactory: AuthServiceFactory,
    private val oAuthFlowService: OAuthFlowService,
    private val staticCredentialFactory: StaticCredentialFactory,
) {
    /**
     * Returns the factory mapping an `authSettingName` to a [CredentialProvider] for one run.
     *
     * The factory yields `null` for every name when [userId] is null: credentials are always
     * user-scoped, so a run without a user gets no provider at all.
     *
     * [caseId] and [emitEvent] make the run interactive ([OAuthFlowService.resolveOAuthCredential]
     * emits its `QuestionEvent` into the case); when either is absent, OAuth types resolve through
     * the direct lookup only. An interactive run must name its agent ([agentName] is a precondition
     * then); a case-less caller such as the tool preview passes all three as null.
     */
    fun forRun(
        namespaceId: UUID,
        userId: UUID?,
        caseId: UUID?,
        agentName: String?,
        emitEvent: ((CaseEvent) -> CaseEvent)?,
    ): (String) -> CredentialProvider? {
        val interactiveRun =
            if (caseId != null && emitEvent != null) {
                require(agentName != null) { "agentName is required for an interactive OAuth run (caseId=$caseId)" }
                InteractiveOAuthRun(caseId = caseId, agentName = agentName, emitEvent = emitEvent)
            } else {
                null
            }
        return { authSettingName ->
            logger.debug { "CredentialProvider invoked for '$authSettingName'" }
            if (userId == null) {
                logger.debug { "CredentialProvider for '$authSettingName': no userId in context, skipping" }
                null
            } else {
                val scopedAuthService = authServiceFactory.create(namespaceId, userId)
                val provider: CredentialProvider = {
                    val setting = scopedAuthService.resolveAuthSetting(authSettingName)
                    logger.debug { "CredentialProvider for '$authSettingName': resolved authType=${setting.authType}" }
                    if (setting.authType in OAUTH_AUTH_TYPES && interactiveRun != null) {
                        resolveInteractiveOAuth(
                            authSettingName = authSettingName,
                            setting = setting,
                            namespaceId = namespaceId,
                            userId = userId,
                            run = interactiveRun,
                        )
                    } else {
                        resolveDirect(
                            authSettingName = authSettingName,
                            setting = setting,
                            scopedAuthService = scopedAuthService,
                            userId = userId,
                            caseId = caseId,
                            emitEvent = emitEvent,
                        )
                    }
                }
                provider
            }
        }
    }

    /**
     * OAuth types: delegate to [OAuthFlowService] for the full lifecycle
     * (check existing -> refresh -> interactive via QuestionEvent).
     */
    private fun resolveInteractiveOAuth(
        authSettingName: String,
        setting: AuthSetting,
        namespaceId: UUID,
        userId: UUID,
        run: InteractiveOAuthRun,
    ): Credential? {
        logger.debug { "CredentialProvider for '$authSettingName': using OAuth flow (authType=${setting.authType})" }
        // NOTE — blocking thread analysis:
        // This helper runs inside the body of the `CredentialProvider` lambda built by
        // `forRun`, so the `runBlocking` below executes when the plugin invokes the
        // provider — not when the provider is created. The lambda is the trigger for the
        // interactive OAuth flow, not a passive carrier of an already-obtained credential.
        //
        // Invocation timing: `CredentialProvider` is called once per run and per
        // MCP integration by `McpHttpToolProvider.provideTools` (via
        // `ToolResolverService.extractTools`) during the tool-resolution phase,
        // before the agent processes any message. It establishes the HTTP MCP
        // connection and is NOT called again on each tool invocation.
        //
        // Blocked thread: `provideTools` is non-suspend (SDK public contract), so
        // the call stack at this point is synchronous. The `runBlocking` therefore
        // blocks a thread from the Kotlin `Dispatchers.IO` pool — NOT a Tomcat/MVC
        // request thread — for up to `agentos.oauth.flow-timeout-minutes` (default
        // 2 min) while waiting for the user to complete browser authorization.
        // The pool ceiling (64 threads by default) implicitly caps the number of
        // concurrent interactive OAuth flows; see OAuthPendingRegistry for the
        // capacity and instance constraints.
        //
        // Why `runBlocking` cannot be removed without touching the SDK: eliminating
        // it requires making `suspend` the entire chain `ToolPlugin.provideTools` →
        // `ToolContext.credentialProvider` → `CredentialProvider` — three elements
        // of the SDK public contract. Tracked in #1198.
        val credential =
            runBlocking {
                oAuthFlowService.resolveOAuthCredential(
                    userId = userId,
                    authSetting = setting,
                    namespaceId = namespaceId,
                    caseId = run.caseId,
                    agentId = UUID.nameUUIDFromBytes(run.agentName.toByteArray()),
                    agentName = run.agentName,
                    emitEvent = run.emitEvent,
                )
            }
        if (credential == null) {
            logger.warn { "CredentialProvider for '$authSettingName': OAuth flow returned null" }
        } else {
            logger.debug { "CredentialProvider for '$authSettingName': OAuth credential resolved" }
        }
        return credential
    }

    /**
     * Direct lookup: the per-user `Credential` row, then the static fallback for non-OAuth types.
     * [caseId] and [emitEvent] only feed the warning that names the missing interactive-flow
     * ingredient when an OAuth type lands here.
     */
    private fun resolveDirect(
        authSettingName: String,
        setting: AuthSetting,
        scopedAuthService: AuthService,
        userId: UUID,
        caseId: UUID?,
        emitEvent: ((CaseEvent) -> CaseEvent)?,
    ): Credential? {
        if (setting.authType in OAUTH_AUTH_TYPES) {
            logger.warn {
                "CredentialProvider for '$authSettingName': OAuth type ${setting.authType} but " +
                    "missing caseId=${caseId != null} or emitEvent=${emitEvent != null}, " +
                    "falling back to direct lookup"
            }
        } else {
            logger.debug {
                "CredentialProvider for '$authSettingName': non-OAuth type ${setting.authType}, " +
                    "using direct credential lookup"
            }
        }
        val credential =
            scopedAuthService.resolveCredential(setting.metadata.id)
                ?: staticCredentialFor(userId, setting)
        if (credential == null) {
            logger.warn {
                "CredentialProvider for '$authSettingName': no credential found for authSetting ${setting.metadata.id}"
            }
        } else {
            logger.debug {
                "CredentialProvider for '$authSettingName': credential resolved " +
                    "(per-user row or static AuthSetting secret)"
            }
        }
        return credential
    }

    /**
     * Static-secret fallback used when no per-user Credential row exists: synthesised in memory
     * from the resolved [setting], never persisted. OAuth types are never synthesised — their
     * credentials only come from [OAuthFlowService].
     */
    private fun staticCredentialFor(
        userId: UUID,
        setting: AuthSetting,
    ): Credential? =
        if (setting.authType in OAUTH_AUTH_TYPES) null else staticCredentialFactory.fromAuthSetting(userId, setting)

    companion object : KLogging() {
        private val OAUTH_AUTH_TYPES =
            setOf(
                AuthType.OAUTH_DISCOVERABLE,
                AuthType.OAUTH_REGISTERED,
                AuthType.OAUTH_CUSTOM,
                AuthType.OAUTH_MCP_DISCOVERABLE,
            )
    }
}

/** The run ingredients the interactive OAuth flow needs; absent as a whole without a case or an event sink. */
private data class InteractiveOAuthRun(
    val caseId: UUID,
    val agentName: String,
    val emitEvent: (CaseEvent) -> CaseEvent,
)
