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
 * Tests of the Auth Setting support of the BASH integration: the variable named after the Auth Setting,
 * injected per credential type, name validation, output redaction, failure modes and lazy, once-per-run
 * resolution of the credential provider.
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

    fun toolRunning(command: String, provider: CredentialProvider?, authSettingName: String? = "GITHUB_TOKEN"): BashTool =
        BashTool(
            toolConfig = BashToolConfig(name = "t", description = "d", command = command),
            integrationConfig = integrationConfig,
            auth = BashRunAuth("TEST", authSettingName, provider),
        )

    fun markerFile() = File(Files.createTempDirectory("bash-auth-spec").toFile(), "must-not-exist")

    // --- variable per credential type ---

    "OAUTH_TOKENS should expose the access token under the Auth Setting name, redacted in the output" {
        val credential = credential(CredentialType.OAUTH_TOKENS, "accessToken" to "oauth-access-secret", "refreshToken" to "oauth-refresh-secret")
        val tool = toolRunning("printf '%s' \"\$GITHUB_TOKEN\"", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "***"
        result.output shouldNotContain "oauth-access-secret"
    }

    "OAUTH_TOKENS should not expose the refresh token, a username or any AGENTOS_AUTH variable" {
        val credential = credential(CredentialType.OAUTH_TOKENS, "accessToken" to "oauth-access-secret", "refreshToken" to "oauth-refresh-secret")
        val tool = toolRunning("env", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.output shouldContain "GITHUB_TOKEN=***"
        result.output shouldNotContain "oauth-refresh-secret"
        result.output shouldNotContain "GITHUB_TOKEN_USERNAME"
        result.output shouldNotContain "AGENTOS_AUTH"
    }

    "BEARER_TOKEN should expose the token" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val tool = toolRunning("printf '%s' \"\$GITHUB_TOKEN\"", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "***"
        result.output shouldNotContain "bearer-secret"
    }

    "API_KEY should expose the key under the Auth Setting name" {
        val credential = credential(CredentialType.API_KEY, "key" to "api-key-secret")
        val tool = toolRunning("printf '%s' \"\$MY_KEY\"", { credential }, authSettingName = "MY_KEY")

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "***"
        result.output shouldNotContain "api-key-secret"
    }

    "BASIC_AUTH should expose the password as NAME and the username as NAME_USERNAME" {
        val credential = credential(CredentialType.BASIC_AUTH, "username" to "alice", "password" to "basic-password-secret")
        val tool = toolRunning("printf '%s|%s' \"\$FORGE_USERNAME\" \"\$FORGE\"", { credential }, authSettingName = "FORGE")

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        // The username is not a secret and stays readable; the password is masked.
        result.output shouldContain "alice|***"
        result.output shouldNotContain "basic-password-secret"
    }

    "BASIC_AUTH should not expose the old password and type variables" {
        val credential = credential(CredentialType.BASIC_AUTH, "username" to "alice", "password" to "basic-password-secret")
        val tool = toolRunning("env", { credential }, authSettingName = "FORGE")

        val result = tool.execute(BashTool.Input(), ctx)

        result.output shouldContain "FORGE=***"
        result.output shouldContain "FORGE_USERNAME=alice"
        result.output shouldNotContain "AGENTOS_AUTH"
        result.output shouldNotContain "FORGE_PASSWORD"
        result.output shouldNotContain "FORGE_TYPE"
    }

    "the secret should be redacted on stderr too" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val tool = toolRunning("echo \"leak: \$GITHUB_TOKEN\" >&2", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.output shouldContain "Stderr:"
        result.output shouldContain "leak: ***"
        result.output shouldNotContain "bearer-secret"
    }

    "the secret should be redacted on a non-zero exit too" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val tool = toolRunning("echo \"\$GITHUB_TOKEN\"; exit 3", { credential })

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

    // --- Auth Setting name: refused before the provider is invoked ---

    listOf("my-token", "1TOKEN", "Company Jira").forEach { name ->
        "an invalid Auth Setting name '$name' should answer AUTH_MISSING, without invoking the provider or running the command" {
            val marker = markerFile()
            val calls = AtomicInteger()
            val provider: CredentialProvider = {
                calls.incrementAndGet()
                credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
            }
            val tool = toolRunning("touch ${marker.absolutePath}", provider, authSettingName = name)

            val result = tool.execute(BashTool.Input(), ctx)

            result.success shouldBe false
            result.errorType shouldBe "AUTH_MISSING"
            result.output shouldBe "Error: ${BashAuthVariables.problemWith(name)}"
            result.output shouldContain "Auth Setting '$name' cannot be used: its name is not a valid environment variable name"
            result.output shouldContain "Rename the Auth Setting"
            calls.get() shouldBe 0
            marker.exists() shouldBe false
        }
    }

    listOf("PATH", "BASH_ENV", "LD_PRELOAD", "DYLD_INSERT_LIBRARIES", "BASH_FUNC_foo", "IFS", "HOME").forEach { name ->
        "a reserved Auth Setting name '$name' should answer AUTH_MISSING, without invoking the provider or running the command" {
            val marker = markerFile()
            val calls = AtomicInteger()
            val provider: CredentialProvider = {
                calls.incrementAndGet()
                credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
            }
            val tool = toolRunning("touch ${marker.absolutePath}", provider, authSettingName = name)

            val result = tool.execute(BashTool.Input(), ctx)

            result.success shouldBe false
            result.errorType shouldBe "AUTH_MISSING"
            result.output shouldContain "Auth Setting '$name' cannot be used: its name is reserved"
            result.output shouldContain "Rename the Auth Setting"
            result.output shouldNotContain "bearer-secret"
            calls.get() shouldBe 0
            marker.exists() shouldBe false
        }
    }

    "reserved names should be matched exactly and case-sensitively" {
        BashAuthVariables.problemWith("PATH") shouldContain "reserved"
        BashAuthVariables.problemWith("path") shouldBe null
        BashAuthVariables.problemWith("PATH_TOKEN") shouldBe null
        BashAuthVariables.problemWith("LDAP_TOKEN") shouldBe null
        BashAuthVariables.problemWith("GITHUB_TOKEN") shouldBe null
        BashAuthVariables.problemWith("_x1") shouldBe null
    }

    listOf<String?>(null, "", "   ").forEach { name ->
        "a provider bound with the Auth Setting name ${name?.let { "'$it'" } ?: "null"} should answer AUTH_MISSING without running the command" {
            val marker = markerFile()
            val calls = AtomicInteger()
            val provider: CredentialProvider = {
                calls.incrementAndGet()
                credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
            }
            val tool = toolRunning("touch ${marker.absolutePath}", provider, authSettingName = name)

            val result = tool.execute(BashTool.Input(), ctx)

            result.errorType shouldBe "AUTH_MISSING"
            result.output shouldContain "No Auth Setting name was provided by the service"
            calls.get() shouldBe 0
            marker.exists() shouldBe false
        }
    }

    // --- failure modes: the command is not run ---

    "a provider returning null should answer AUTH_MISSING without running the command" {
        val marker = markerFile()
        val tool = toolRunning("touch ${marker.absolutePath}", { null })

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe false
        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldBe
            "Error: Auth Setting 'GITHUB_TOKEN' gave no credential: its secret is empty, " +
            "or it is an OAuth setting whose authorization was not completed."
        marker.exists() shouldBe false
    }

    "a credential with blank material should answer AUTH_MISSING naming the setting and the type" {
        val marker = markerFile()
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "   ")
        val tool = toolRunning("touch ${marker.absolutePath}", { credential })

        val result = tool.execute(BashTool.Input(), ctx)

        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldBe "Error: Auth Setting 'GITHUB_TOKEN' (BEARER_TOKEN) carries no usable secret."
        marker.exists() shouldBe false
    }

    "a basic-auth credential missing its password should answer AUTH_MISSING" {
        val credential = credential(CredentialType.BASIC_AUTH, "username" to "alice")
        val tool = toolRunning("echo hi", { credential })

        tool.execute(BashTool.Input(), ctx).errorType shouldBe "AUTH_MISSING"
    }

    "a provider that throws should answer AUTH_MISSING naming the setting, without leaking the exception" {
        val marker = markerFile()
        val tool = toolRunning("touch ${marker.absolutePath}", { throw IllegalStateException("boom-secret-detail") })

        val result = shouldNotThrowAny { tool.execute(BashTool.Input(), ctx) }

        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldContain "Error: Auth Setting 'GITHUB_TOKEN' could not be resolved for this run."
        result.output shouldContain "exactly this name (case-sensitive)"
        result.output shouldContain "The service log has the detail."
        result.output shouldNotContain "boom-secret-detail"
        result.errorMessage shouldNotContain "boom-secret-detail"
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
            auth = BashRunAuth("TEST", "GITHUB_TOKEN") { calls.incrementAndGet(); null },
        )

        tool.execute(BashTool.Input(parameters = null), ctx).errorType shouldBe "INVALID_INPUT"

        calls.get() shouldBe 0
    }

    // --- no provider: as before ---

    "without a provider the command should run with no auth variable" {
        val tool = toolRunning(
            "printf '%s|%s' \"\${FORGE-unset}\" \"\${FORGE_USERNAME-unset}\"",
            provider = null,
            authSettingName = null,
        )

        val result = tool.execute(BashTool.Input(), ctx)

        result.success shouldBe true
        result.output shouldContain "unset|unset"
    }

    "a BashTool built without an auth argument should keep running as before" {
        val tool = BashTool(
            toolConfig = BashToolConfig(name = "t", description = "d", command = "echo hello"),
            integrationConfig = integrationConfig,
        )

        tool.execute(BashTool.Input(), ctx).output shouldContain "hello"
    }

    // --- provideTools: the name comes from the ToolContext; laziness, once per run ---

    "provideTools should not invoke the credential provider" {
        val calls = AtomicInteger()
        val provider: CredentialProvider = { calls.incrementAndGet(); null }
        val context = ctx.copy(credentialProvider = provider, authSettingName = "GITHUB_TOKEN")
        val config = jacksonObjectMapper().readTree(
            """{"workingDirectory": "/tmp", "tools": [{"name": "a", "description": "d", "command": "echo a"}]}""",
        )

        BashToolProvider().provideTools(config, "TEST", context) shouldHaveSize 1

        calls.get() shouldBe 0
    }

    "provideTools should pass the ToolContext authSettingName through, end to end" {
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val context = ctx.copy(credentialProvider = { credential }, authSettingName = "GITHUB_TOKEN")
        val config = jacksonObjectMapper().readTree(
            """
            {"workingDirectory": "${System.getProperty("java.io.tmpdir")}", "tools": [
                {"name": "a", "description": "d", "command": "printf %s \"${'$'}GITHUB_TOKEN\""}
            ]}
            """,
        )
        val tool = BashToolProvider().provideTools(config, "TEST", context).single()

        val result = tool.executeWithJson("{}", ctx)

        result.success shouldBe true
        result.output shouldContain "***"
        result.output shouldNotContain "bearer-secret"
    }

    "provideTools should refuse a provider bound without an Auth Setting name" {
        val marker = markerFile()
        val calls = AtomicInteger()
        val context = ctx.copy(credentialProvider = { calls.incrementAndGet(); null })
        val config = jacksonObjectMapper().readTree(
            """
            {"workingDirectory": "${System.getProperty("java.io.tmpdir")}", "tools": [
                {"name": "a", "description": "d", "command": "touch ${marker.absolutePath}"}
            ]}
            """,
        )
        val tool = BashToolProvider().provideTools(config, "TEST", context).single()

        val result = tool.executeWithJson("{}", ctx)

        result.errorType shouldBe "AUTH_MISSING"
        result.output shouldContain "No Auth Setting name was provided by the service"
        calls.get() shouldBe 0
        marker.exists() shouldBe false
    }

    "the reason of a missing credential should never contain a secret value" {
        val environment = BashAuthEnvironment.from(
            "FORGE",
            credential(CredentialType.BASIC_AUTH, "username" to "alice", "password" to "  "),
        )

        environment.shouldBeInstanceOf<BashAuthEnvironment.Missing>()
        environment.reason shouldBe "Auth Setting 'FORGE' (BASIC_AUTH) carries no usable secret."
        environment.reason shouldNotContain "alice"
    }

    "the tools of one integration should share a single resolution" {
        val calls = AtomicInteger()
        val credential = credential(CredentialType.BEARER_TOKEN, "token" to "bearer-secret")
        val provider: CredentialProvider = { calls.incrementAndGet(); credential }
        val config = jacksonObjectMapper().readTree(
            """
            {"workingDirectory": "${System.getProperty("java.io.tmpdir")}", "tools": [
                {"name": "a", "description": "d", "command": "echo \"a:${'$'}GITHUB_TOKEN\""},
                {"name": "b", "description": "d", "command": "echo \"b:${'$'}GITHUB_TOKEN\""}
            ]}
            """,
        )
        val tools = BashToolProvider().provideTools(
            config,
            "TEST",
            ctx.copy(credentialProvider = provider, authSettingName = "GITHUB_TOKEN"),
        )
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
        val auth = BashRunAuth("TEST", "GITHUB_TOKEN") { calls.incrementAndGet(); null }

        auth.environment().shouldBeInstanceOf<BashAuthEnvironment.Missing>()
        auth.environment().shouldBeInstanceOf<BashAuthEnvironment.Missing>()

        calls.get() shouldBe 1
    }

    // --- redaction of the environment's own text ---

    "toString of the resolved variables should not contain the secret" {
        val environment = BashAuthEnvironment.from(
            "FORGE",
            credential(CredentialType.BASIC_AUTH, "username" to "alice", "password" to "basic-password-secret"),
        )

        environment.shouldBeInstanceOf<BashAuthEnvironment.Variables>()
        environment.toString() shouldNotContain "basic-password-secret"
        environment.toString() shouldContain "FORGE_USERNAME"
    }
})
