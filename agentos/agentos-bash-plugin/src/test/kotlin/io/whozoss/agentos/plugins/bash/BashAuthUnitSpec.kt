package io.whozoss.agentos.plugins.bash

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.sdk.auth.CredentialProvider
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import io.whozoss.agentos.sdk.tool.ToolContext
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests of the Auth Setting support of the BASH integration: variables injected per credential type,
 * output redaction, failure modes and lazy, once-per-run resolution of the credential provider.
 *
 * Commands run for real (printf / touch) so that what the shell actually sees is what is asserted.
 */
class BashAuthUnitSpec : StringSpec({

    val integrationConfig = BashIntegrationConfig(
        workingDirectory = System.getProperty("java.io.tmpdir"),
        defaultTimeoutSeconds = 10L,
    )
    val ctx = ToolContext(UUID.randomUUID(), null, null, emptyList())

    fun credential(type: CredentialType, vararg data: Pair<String, String>) =
        Credential(
            userId = UUID.randomUUID(),
            authSettingId = UUID.randomUUID(),
            credentialType = type,
            data = mapOf(*data),
        )

    fun toolRunning(command: String, provider: CredentialProvider?): BashTool =
        BashTool(
            toolConfig = BashToolConfig(name = "t", description = "d", command = command),
            integrationConfig = integrationConfig,
            auth = BashRunAuth("TEST", provider),
        )

    // --- variables per credential type ---

    "OAUTH_TOKENS should expose the access token, redacted in the output" {
        val credential = credential(CredentialType.OAUTH_TOKENS, "accessToken" to "oauth-access-secret", "refreshToken" to "oauth-refresh-secret")
        val tool = toolRunning("printf '%s|%s' \"\$AGENTOS_AUTH_TYPE\" \"\$AGENTOS_AUTH_TOKEN\"", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "OAUTH_TOKENS|***"
        result.output shouldNotContain "oauth-access-secret"
    }

    "OAUTH_TOKENS should not expose the refresh token" {
        val credential = credential(CredentialType.OAUTH_TOKENS, "accessToken" to "oauth-access-secret", "refreshToken" to "oauth-refresh-secret")
        val tool = toolRunning("env", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.output shouldNotContain "oauth-refresh-secret"
        result.output shouldNotContain "AGENTOS_AUTH_USERNAME"
    }

    "BEARER_TOKEN should expose the token" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val tool = toolRunning("printf '%s|%s' \"\$AGENTOS_AUTH_TYPE\" \"\$AGENTOS_AUTH_TOKEN\"", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "BEARER_TOKEN|***"
        result.output shouldNotContain "bearer-secret"
    }

    "API_KEY should expose the key as the token" {
        val credential = credential(CredentialType.API_KEY, "key" to "api-key-secret")
        val tool = toolRunning("printf '%s|%s' \"\$AGENTOS_AUTH_TYPE\" \"\$AGENTOS_AUTH_TOKEN\"", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "API_KEY|***"
        result.output shouldNotContain "api-key-secret"
    }

    "BASIC_AUTH should expose username, password and the password as the token" {
        val credential = credential(CredentialType.BASIC_AUTH, "username" to "alice", "password" to "basic-password-secret")
        val tool = toolRunning(
            "printf '%s|%s|%s|%s' \"\$AGENTOS_AUTH_TYPE\" \"\$AGENTOS_AUTH_USERNAME\" \"\$AGENTOS_AUTH_PASSWORD\" \"\$AGENTOS_AUTH_TOKEN\"",
            { credential },
        )

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        // The username is not a secret and stays readable; the password, under both names, is masked.
        result.output shouldContain "BASIC_AUTH|alice|***|***"
        result.output shouldNotContain "basic-password-secret"
    }

    "the secret should be redacted on stderr too" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val tool = toolRunning("echo \"leak: \$AGENTOS_AUTH_TOKEN\" >&2", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.output shouldContain "Stderr:"
        result.output shouldContain "leak: ***"
        result.output shouldNotContain "bearer-secret"
    }

    "the secret should be redacted on a non-zero exit too" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val tool = toolRunning("echo \"\$AGENTOS_AUTH_TOKEN\"; exit 3", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.errorType shouldBe "NONZERO_EXIT"
        result.output shouldContain "***"
        result.output shouldNotContain "bearer-secret"
    }

    "a secret shorter than 4 chars should not be redacted" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "abc")
        val tool = toolRunning("echo abcdef", { credential })

        tool.execute(BashTool.Input(), ctx).output shouldContain "abcdef"
    }

    // --- failure modes: the command is not run ---

    "a provider returning null should answer AUTH_MISSING without running the command" {
        val marker = File(Files.createTempDirectory("bash-auth-spec").toFile(), "must-not-exist")
        val tool = toolRunning("touch ${marker.absolutePath}", { null })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe false
        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldContain "Error"
        result.output shouldContain "no credential available"
        marker.exists() shouldBe false
    }

    "a credential with blank material should answer AUTH_MISSING naming the type" {
        val marker = File(Files.createTempDirectory("bash-auth-spec").toFile(), "must-not-exist")
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "   ")
        val tool = toolRunning("touch ${marker.absolutePath}", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldContain "BEARER_TOKEN"
        marker.exists() shouldBe false
    }

    "a basic-auth credential missing its password should answer AUTH_MISSING" {
        val credential = credential(CredentialType.BASIC_AUTH, "username" to "alice")
        val tool = toolRunning("echo hi", { credential })

        tool.execute(BashTool.Input(), ctx).errorType shouldBe "AUTH_MISSING"
    }

    "a provider that throws should answer AUTH_MISSING without leaking the exception" {
        val marker = File(Files.createTempDirectory("bash-auth-spec").toFile(), "must-not-exist")
        val tool = toolRunning("touch ${marker.absolutePath}", { error("boom-internal-detail") })

        val result = shouldNotThrowAny { tool.execute(BashTool.Input(), ctx) }

        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldNotContain "boom-internal-detail"
        marker.exists() shouldBe false
    }

    "an invalid call should not resolve the credential" {
        val calls = AtomicInteger()
        val tool = BashTool(
            toolConfig = BashToolConfig(
                name = "t",
                description = "d",
                command = "echo PARAMETERS",
                parametersDescription = "text",
            ),
            integrationConfig = integrationConfig,
            auth = BashRunAuth("TEST") { calls.incrementAndGet(); null },
        )

        tool.execute(BashTool.Input(parameters = null), ctx).errorType shouldBe "INVALID_INPUT"

        calls.get() shouldBe 0
    }

    // --- no provider: as before ---

    "without a provider the command should run with no auth variable" {
        val tool = toolRunning(
            "printf '%s|%s|%s' \"\${AGENTOS_AUTH_TOKEN-unset}\" \"\${AGENTOS_AUTH_TYPE-unset}\" \"\${AGENTOS_AUTH_USERNAME-unset}\"",
            provider = null,
        )

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "unset|unset|unset"
    }

    "a BashTool built without an auth argument should keep running as before" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "t", description = "d", command = "echo hello"),
            integrationConfig = integrationConfig,
        )

        tool.execute(BashTool.Input(), ctx).output shouldContain "hello"
    }

    // --- laziness, once per run ---

    "provideTools should not invoke the credential provider" {
        val calls = AtomicInteger()
        val provider: CredentialProvider = { calls.incrementAndGet(); null }
        val context = ctx.copy(credentialProvider = provider)
        val config = jacksonObjectMapper().readTree(
            """{"workingDirectory": "/tmp", "tools": [{"name": "a", "description": "d", "command": "echo a"}]}""",
        )

        BashToolProvider().provideTools(config, "TEST", context) shouldHaveSize 1

        calls.get() shouldBe 0
    }

    "the tools of one integration should share a single resolution" {
        val calls = AtomicInteger()
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val provider: CredentialProvider = { calls.incrementAndGet(); credential }
        val config = jacksonObjectMapper().readTree(
            """
            {"workingDirectory": "${System.getProperty("java.io.tmpdir")}", "tools": [
                {"name": "a", "description": "d", "command": "echo \"a:${'$'}AGENTOS_AUTH_TOKEN\""},
                {"name": "b", "description": "d", "command": "echo \"b:${'$'}AGENTOS_AUTH_TOKEN\""}
            ]}
            """,
        )
        val tools = BashToolProvider().provideTools(config, "TEST", ctx.copy(credentialProvider = provider))
        tools shouldHaveSize 2

        val first = tools[0].executeWithJson("{}", ctx)
        val second = tools[1].executeWithJson("{}", ctx)
        val third = tools[0].executeWithJson("{}", ctx)

        first.output shouldContain "a:***"
        second.output shouldContain "b:***"
        third.output shouldContain "a:***"
        calls.get() shouldBe 1
    }

    "a missing credential should be remembered for the rest of the run" {
        val calls = AtomicInteger()
        val auth = BashRunAuth("TEST") { calls.incrementAndGet(); null }

        auth.environment().shouldBeInstanceOf<BashAuthEnvironment.Missing>()
        auth.environment().shouldBeInstanceOf<BashAuthEnvironment.Missing>()

        calls.get() shouldBe 1
    }

    // --- redaction of the environment's own text ---

    "toString of the resolved variables should not contain the secret" {
        val environment = BashAuthEnvironment.from(
            credential(CredentialType.BASIC_AUTH, "username" to "alice", "password" to "basic-password-secret"),
        )

        environment.shouldBeInstanceOf<BashAuthEnvironment.Variables>()
        environment.toString() shouldNotContain "basic-password-secret"
    }
})
