package io.whozoss.agentos.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import io.whozoss.agentos.authSetting.ApiKeyAuthSetting
import io.whozoss.agentos.authSetting.AuthSetting
import io.whozoss.agentos.authSetting.AuthType
import io.whozoss.agentos.authSetting.BasicAuthAuthSetting
import io.whozoss.agentos.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.authSetting.OAuthCustomAuthSetting
import io.whozoss.agentos.authSetting.OAuthDiscoverableAuthSetting
import io.whozoss.agentos.authSetting.OAuthMcpDiscoverableAuthSetting
import io.whozoss.agentos.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.encryption.FieldEncryptor
import io.whozoss.agentos.encryption.NoOpFieldEncryptor
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.reflect.full.primaryConstructor

/**
 * Unit tests for [StaticCredentialFactory].
 *
 * The per-[AuthType] cases are generated from [AuthType.entries]: adding an enum value without
 * deciding whether it is a static secret type or not fails [expectedCredentialByType] lookup with
 * an explicit message, and [sampleSetting] stops compiling because its `when` is exhaustive.
 */
class StaticCredentialFactoryUnitSpec : StringSpec() {
    private val userId: UUID = UUID.randomUUID()
    private val authSettingId: UUID = UUID.randomUUID()
    private val factory = StaticCredentialFactory(NoOpFieldEncryptor())

    /** Expected `(credentialType, data)` per [AuthType]; `null` means "no static credential". */
    private val expectedCredentialByType: Map<AuthType, Pair<CredentialType, Map<String, String>>?> =
        mapOf(
            AuthType.API_KEY to (CredentialType.API_KEY to mapOf("key" to "sk-static")),
            AuthType.BEARER_TOKEN to (CredentialType.BEARER_TOKEN to mapOf("token" to "tok-static")),
            AuthType.BASIC_AUTH to
                (CredentialType.BASIC_AUTH to mapOf("username" to "alice", "password" to "pw-static")),
            AuthType.OAUTH_DISCOVERABLE to null,
            AuthType.OAUTH_REGISTERED to null,
            AuthType.OAUTH_CUSTOM to null,
            AuthType.OAUTH_MCP_DISCOVERABLE to null,
        )

    private fun sampleSetting(type: AuthType): AuthSetting {
        val metadata = EntityMetadata(id = authSettingId)
        return when (type) {
            AuthType.API_KEY ->
                ApiKeyAuthSetting(metadata = metadata, name = "static-$type", apiKey = "sk-static")
            AuthType.BEARER_TOKEN ->
                BearerTokenAuthSetting(metadata = metadata, name = "static-$type", token = "tok-static")
            AuthType.BASIC_AUTH ->
                BasicAuthAuthSetting(
                    metadata = metadata,
                    name = "static-$type",
                    username = "alice",
                    password = "pw-static",
                )
            AuthType.OAUTH_DISCOVERABLE ->
                OAuthDiscoverableAuthSetting(
                    metadata = metadata,
                    name = "static-$type",
                    discoveryUrl = "https://idp.example.com/.well-known/openid-configuration",
                    clientId = "client",
                    clientSecret = "secret-oauth",
                )
            AuthType.OAUTH_REGISTERED ->
                OAuthRegisteredAuthSetting(
                    metadata = metadata,
                    name = "static-$type",
                    clientId = "client",
                    clientSecret = "secret-oauth",
                    authorizationUrl = "https://idp.example.com/authorize",
                    tokenUrl = "https://idp.example.com/token",
                )
            AuthType.OAUTH_CUSTOM ->
                OAuthCustomAuthSetting(
                    metadata = metadata,
                    name = "static-$type",
                    clientId = "client",
                    clientSecret = "secret-oauth",
                    authorizationUrl = "https://idp.example.com/authorize",
                    tokenUrl = "https://idp.example.com/token",
                )
            AuthType.OAUTH_MCP_DISCOVERABLE ->
                OAuthMcpDiscoverableAuthSetting(
                    metadata = metadata,
                    name = "static-$type",
                    resourceUrl = "https://mcp.example.com",
                    clientSecret = "secret-oauth",
                )
        }
    }

    private val factoryLogger = LoggerFactory.getLogger(StaticCredentialFactory::class.java) as Logger
    private val logCaptor = ListAppender<ILoggingEvent>()

    /** Attaches [logCaptor] to the factory logger; detached again by the `afterTest` hook. */
    private fun captureLogs(): ListAppender<ILoggingEvent> {
        logCaptor.start()
        factoryLogger.addAppender(logCaptor)
        return logCaptor
    }

    init {
        afterTest { factoryLogger.detachAppender(logCaptor) }

        "every AuthType has an explicit static-credential expectation" {
            AuthType.entries.forEach { type ->
                withClue("AuthType.$type has no entry in expectedCredentialByType: is it a static secret type?") {
                    expectedCredentialByType.containsKey(type) shouldBe true
                }
            }
        }

        AuthType.entries.forEach { type ->
            val expected = expectedCredentialByType[type]
            if (expected == null) {
                "fromAuthSetting returns null for $type (OAuth types are owned by OAuthFlowService)" {
                    factory.fromAuthSetting(userId, sampleSetting(type)).shouldBeNull()
                }
            } else {
                "fromAuthSetting synthesises a ${expected.first} credential from a $type setting" {
                    val credential = factory.fromAuthSetting(userId, sampleSetting(type)).shouldNotBeNull()

                    credential.credentialType shouldBe expected.first
                    credential.data shouldBe expected.second
                    credential.userId shouldBe userId
                    credential.authSettingId shouldBe authSettingId
                }
            }
        }

        "fromAuthSetting returns null and warns without the value when the API key is blank" {
            val appender = captureLogs()
            val setting =
                ApiKeyAuthSetting(metadata = EntityMetadata(id = authSettingId), name = "blank-key", apiKey = "   ")

            factory.fromAuthSetting(userId, setting).shouldBeNull()

            val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            warnings.size shouldBe 1
            warnings.single() shouldContain "blank-key"
            warnings.single() shouldContain "API_KEY"
        }

        "fromAuthSetting returns null when the bearer token is blank" {
            val setting =
                BearerTokenAuthSetting(metadata = EntityMetadata(id = authSettingId), name = "blank-token", token = "")

            factory.fromAuthSetting(userId, setting).shouldBeNull()
        }

        "fromAuthSetting returns null when the basic-auth password is blank" {
            val setting =
                BasicAuthAuthSetting(
                    metadata = EntityMetadata(id = authSettingId),
                    name = "blank-password",
                    username = "alice",
                    password = "",
                )

            factory.fromAuthSetting(userId, setting).shouldBeNull()
        }

        "fromAuthSetting never logs the secret value" {
            val appender = captureLogs()
            val setting =
                ApiKeyAuthSetting(
                    metadata = EntityMetadata(id = authSettingId),
                    name = "logged-key",
                    apiKey = "sk-very-secret",
                )

            factory.fromAuthSetting(userId, setting).shouldNotBeNull()

            appender.list.forEach { event -> event.formattedMessage shouldNotContain "sk-very-secret" }
        }

        "the synthesised credential carries a fresh transient metadata, not the setting's" {
            val credential = factory.fromAuthSetting(userId, sampleSetting(AuthType.API_KEY)).shouldNotBeNull()

            (credential.metadata.id == authSettingId) shouldBe false
        }

        "construction warns exactly once when the field encryptor is the no-op one" {
            val appender = captureLogs()

            StaticCredentialFactory(NoOpFieldEncryptor())

            val warnings = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
            warnings.size shouldBe 1
            warnings.single() shouldContain "plaintext"
        }

        "construction does not warn with a real field encryptor" {
            val appender = captureLogs()

            StaticCredentialFactory(mockk<FieldEncryptor>())

            appender.list.filter { it.level == Level.WARN }.size shouldBe 0
        }

        "the factory depends on nothing but the field encryptor (no persistence dependency)" {
            val parameterTypes =
                StaticCredentialFactory::class.primaryConstructor
                    .shouldNotBeNull()
                    .parameters
                    .map { it.type.classifier }

            parameterTypes shouldContainExactly listOf(FieldEncryptor::class)
        }
    }
}
