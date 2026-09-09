package io.whozoss.agentos.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.authSetting.ApiKeyAuthSetting
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Unit tests for [CredentialProviderFactory], the run-scoped `CredentialProvider` builder that
 * `AgentServiceImpl` hands to `ToolResolverService` and that the integration-config tool preview
 * reuses without a case.
 */
class CredentialProviderFactoryUnitSpec : StringSpec() {
    private val authServiceFactory: AuthServiceFactory = mockk()
    private val oAuthFlowService: OAuthFlowService = mockk()
    // Strict: the static fallback must only run on the exact path under test (non-OAuth type, no
    // per-user row). A relaxed mock would silently return a Credential and mask a wrong dispatch.
    private val staticCredentialFactory: StaticCredentialFactory = mockk()
    private val scopedAuthService: AuthService = mockk()
    private val factory =
        CredentialProviderFactory(
            authServiceFactory = authServiceFactory,
            oAuthFlowService = oAuthFlowService,
            staticCredentialFactory = staticCredentialFactory,
        )

    private val namespaceId: UUID = UUID.randomUUID()
    private val userId: UUID = UUID.randomUUID()
    private val caseId: UUID = UUID.randomUUID()
    private val authSettingId: UUID = UUID.randomUUID()
    private val emitEvent: (CaseEvent) -> CaseEvent = { it }

    private val oauthSetting =
        OAuthRegisteredAuthSetting(
            metadata = EntityMetadata(id = authSettingId),
            name = "my-oauth",
            clientId = "client-id",
            clientSecret = "client-secret",
            authorizationUrl = "https://provider.example.com/auth",
            tokenUrl = "https://provider.example.com/token",
        )
    private val apiKeySetting =
        ApiKeyAuthSetting(
            metadata = EntityMetadata(id = authSettingId),
            name = "my-api-key",
            apiKey = "sk-secret-value",
        )
    private val bearerSetting =
        BearerTokenAuthSetting(
            metadata = EntityMetadata(id = authSettingId),
            name = "my-bearer",
            token = "tok-secret-value",
        )

    private fun credential(
        type: CredentialType,
        data: Map<String, String>,
    ) = Credential(
        metadata = EntityMetadata(),
        userId = userId,
        authSettingId = authSettingId,
        credentialType = type,
        data = data,
    )

    private fun forUserRun(
        caseId: UUID? = this.caseId,
        agentName: String? = "my-agent",
        emitEvent: ((CaseEvent) -> CaseEvent)? = this.emitEvent,
    ) = factory.forRun(
        namespaceId = namespaceId,
        userId = userId,
        caseId = caseId,
        agentName = agentName,
        emitEvent = emitEvent,
    )

    private val factoryLogger = LoggerFactory.getLogger(CredentialProviderFactory::class.java) as Logger
    private val logCaptor = ListAppender<ILoggingEvent>()

    init {
        beforeTest {
            clearAllMocks()
            every { authServiceFactory.create(namespaceId, userId) } returns scopedAuthService
        }
        afterTest {
            factoryLogger.detachAppender(logCaptor)
            logCaptor.stop()
        }

        "forRun yields no provider when the run has no userId" {
            val provider =
                factory.forRun(
                    namespaceId = namespaceId,
                    userId = null,
                    caseId = caseId,
                    agentName = "my-agent",
                    emitEvent = emitEvent,
                )

            provider("my-api-key").shouldBeNull()
            verify(exactly = 0) { authServiceFactory.create(any(), any()) }
            verify(exactly = 0) { staticCredentialFactory.fromAuthSetting(any(), any()) }
        }

        "forRun routes an OAuth type through OAuthFlowService when caseId and emitEvent are present" {
            every { scopedAuthService.resolveAuthSetting("my-oauth") } returns oauthSetting
            val expected = credential(CredentialType.OAUTH_TOKENS, mapOf("accessToken" to "oauth-tok"))
            coEvery {
                oAuthFlowService.resolveOAuthCredential(
                    userId = userId,
                    authSetting = oauthSetting,
                    namespaceId = namespaceId,
                    caseId = caseId,
                    agentId = UUID.nameUUIDFromBytes("my-agent".toByteArray()),
                    agentName = "my-agent",
                    emitEvent = emitEvent,
                )
            } returns expected

            val resolved = forUserRun()("my-oauth").shouldNotBeNull().invoke()

            resolved shouldBe expected
            coVerify(exactly = 1) {
                oAuthFlowService.resolveOAuthCredential(any(), any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 0) { scopedAuthService.resolveCredential(any()) }
            verify(exactly = 0) { staticCredentialFactory.fromAuthSetting(any(), any()) }
        }

        "forRun falls back to the direct lookup for an OAuth type without a caseId, never synthesising" {
            every { scopedAuthService.resolveAuthSetting("my-oauth") } returns oauthSetting
            every { scopedAuthService.resolveCredential(authSettingId) } returns null

            forUserRun(caseId = null)("my-oauth").shouldNotBeNull().invoke().shouldBeNull()

            verify(exactly = 1) { scopedAuthService.resolveCredential(authSettingId) }
            coVerify(exactly = 0) {
                oAuthFlowService.resolveOAuthCredential(any(), any(), any(), any(), any(), any(), any())
            }
            verify(exactly = 0) { staticCredentialFactory.fromAuthSetting(any(), any()) }
        }

        "forRun falls back to the direct lookup for an OAuth type without an emitEvent" {
            every { scopedAuthService.resolveAuthSetting("my-oauth") } returns oauthSetting
            val stored = credential(CredentialType.OAUTH_TOKENS, mapOf("accessToken" to "stored-tok"))
            every { scopedAuthService.resolveCredential(authSettingId) } returns stored

            forUserRun(emitEvent = null)("my-oauth").shouldNotBeNull().invoke() shouldBe stored

            coVerify(exactly = 0) {
                oAuthFlowService.resolveOAuthCredential(any(), any(), any(), any(), any(), any(), any())
            }
        }

        "forRun names the missing interactive ingredient in the fallback warning, as the inline lambda did" {
            factoryLogger.level = Level.TRACE
            logCaptor.start()
            factoryLogger.addAppender(logCaptor)
            every { scopedAuthService.resolveAuthSetting("my-oauth") } returns oauthSetting
            every { scopedAuthService.resolveCredential(authSettingId) } returns null

            forUserRun(caseId = null, agentName = null)("my-oauth").shouldNotBeNull().invoke().shouldBeNull()

            val fallbackWarning =
                logCaptor.list.single {
                    it.level == Level.WARN && "falling back to direct lookup" in it.formattedMessage
                }
            fallbackWarning.formattedMessage shouldContain
                "OAuth type OAUTH_REGISTERED but missing caseId=false or emitEvent=true, falling back to direct lookup"
            fallbackWarning.formattedMessage shouldNotContain "agentName"
        }

        "forRun rejects an interactive run (caseId and emitEvent) without an agentName up front" {
            val failure = shouldThrow<IllegalArgumentException> { forUserRun(agentName = null) }

            failure.message shouldContain "agentName"
            verify(exactly = 0) { authServiceFactory.create(any(), any()) }
        }

        "forRun lets the per-user Credential row win for a non-OAuth type" {
            every { scopedAuthService.resolveAuthSetting("my-api-key") } returns apiKeySetting
            val stored = credential(CredentialType.API_KEY, mapOf("key" to "sk-stored"))
            every { scopedAuthService.resolveCredential(authSettingId) } returns stored

            forUserRun()("my-api-key").shouldNotBeNull().invoke() shouldBe stored

            verify(exactly = 1) { scopedAuthService.resolveCredential(authSettingId) }
            verify(exactly = 0) { staticCredentialFactory.fromAuthSetting(any(), any()) }
            coVerify(exactly = 0) {
                oAuthFlowService.resolveOAuthCredential(any(), any(), any(), any(), any(), any(), any())
            }
        }

        "forRun synthesises a static credential when no per-user row exists, without persisting it" {
            every { scopedAuthService.resolveAuthSetting("my-bearer") } returns bearerSetting
            every { scopedAuthService.resolveCredential(authSettingId) } returns null
            val synthesised = credential(CredentialType.BEARER_TOKEN, mapOf("token" to "tok-secret-value"))
            every { staticCredentialFactory.fromAuthSetting(userId, bearerSetting) } returns synthesised

            forUserRun()("my-bearer").shouldNotBeNull().invoke() shouldBe synthesised

            verify(exactly = 1) { staticCredentialFactory.fromAuthSetting(userId, bearerSetting) }
            verify(exactly = 0) { scopedAuthService.storeCredential(any()) }
        }

        "forRun returns null for a non-OAuth type with neither a per-user row nor a static secret" {
            val blankSetting =
                ApiKeyAuthSetting(metadata = EntityMetadata(id = authSettingId), name = "blank-key", apiKey = "")
            every { scopedAuthService.resolveAuthSetting("blank-key") } returns blankSetting
            every { scopedAuthService.resolveCredential(authSettingId) } returns null
            every { staticCredentialFactory.fromAuthSetting(userId, blankSetting) } returns null

            forUserRun()("blank-key").shouldNotBeNull().invoke().shouldBeNull()

            verify(exactly = 0) { scopedAuthService.storeCredential(any()) }
        }

        "forRun never writes a secret value to the log on any path" {
            factoryLogger.level = Level.TRACE
            logCaptor.start()
            factoryLogger.addAppender(logCaptor)
            every { scopedAuthService.resolveAuthSetting("my-api-key") } returns apiKeySetting
            every { scopedAuthService.resolveCredential(authSettingId) } returns null
            every { staticCredentialFactory.fromAuthSetting(userId, apiKeySetting) } returns
                credential(CredentialType.API_KEY, mapOf("key" to "sk-secret-value"))
            every { scopedAuthService.resolveAuthSetting("my-oauth") } returns oauthSetting
            coEvery {
                oAuthFlowService.resolveOAuthCredential(any(), any(), any(), any(), any(), any(), any())
            } returns credential(CredentialType.OAUTH_TOKENS, mapOf("accessToken" to "oauth-secret-value"))

            forUserRun()("my-api-key").shouldNotBeNull().invoke().shouldNotBeNull()
            forUserRun()("my-oauth").shouldNotBeNull().invoke().shouldNotBeNull()
            forUserRun(caseId = null)("my-oauth").shouldNotBeNull()

            val messages = logCaptor.list.map { it.formattedMessage }
            messages.isEmpty() shouldBe false
            messages.forEach { message ->
                message shouldNotContain "sk-secret-value"
                message shouldNotContain "oauth-secret-value"
                message shouldNotContain "client-secret"
            }
        }
    }
}
