package io.whozoss.agentos.plugins.kotlin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

private const val HOME = "/home/romain"
private val userInstall = Path.of("$HOME/kotlin-lsp/server/bin/intellij-server")
private val fromEnvironment = Path.of("/opt/lsp/intellij-server")
private val fromIntegration = Path.of("/srv/lsp/intellij-server")

private fun resolver(
    executable: Set<Path>,
    env: Map<String, String> = emptyMap(),
) = KotlinLspBinaryResolver(env = env::get, userHome = HOME, isExecutable = { it in executable })

class KotlinLspBinaryResolverUnitSpec :
    StringSpec({
        "the integration parameter wins over the environment variable and the user install" {
            // given
            val resolver =
                resolver(
                    executable = setOf(fromIntegration, fromEnvironment, userInstall),
                    env = mapOf(ENV_SERVER_BINARY to "$fromEnvironment"),
                )

            // when
            val resolved = resolver.resolve(configured = "$fromIntegration")

            // then
            resolved shouldBe fromIntegration
        }

        "an unusable integration parameter fails and names it, instead of falling back silently" {
            // given
            val resolver = resolver(executable = setOf(fromEnvironment, userInstall), env = mapOf(ENV_SERVER_BINARY to "$fromEnvironment"))

            // when
            val error = shouldThrow<IllegalStateException> { resolver.resolve(configured = "$fromIntegration") }

            // then
            error.message shouldContain KotlinConfigParser.SERVER_BINARY
            error.message shouldContain "$fromIntegration"
        }

        "a blank integration parameter is ignored" {
            resolver(executable = setOf(userInstall)).resolve(configured = "  ") shouldBe userInstall
        }

        "the environment variable wins over the user install" {
            val resolver = resolver(executable = setOf(fromEnvironment, userInstall), env = mapOf(ENV_SERVER_BINARY to "$fromEnvironment"))

            resolver.resolve() shouldBe fromEnvironment
        }

        "the user install is the fallback" {
            resolver(executable = setOf(userInstall)).resolve() shouldBe userInstall
        }

        "a binary that would sit in the project is never looked for" {
            val bundled = Path.of("/work/project/integration/integration-kotlin-lsp/kotlin-lsp/server/bin/intellij-server")

            val error = shouldThrow<IllegalStateException> { resolver(executable = setOf(bundled)).resolve() }

            error.message shouldContain "not found"
        }

        "an unusable environment variable fails instead of falling back silently" {
            val resolver = resolver(executable = setOf(userInstall), env = mapOf(ENV_SERVER_BINARY to "$fromEnvironment"))

            val error = shouldThrow<IllegalStateException> { resolver.resolve() }

            error.message shouldContain ENV_SERVER_BINARY
            error.message shouldContain "$fromEnvironment"
        }

        "a blank environment variable is ignored" {
            resolver(executable = setOf(userInstall), env = mapOf(ENV_SERVER_BINARY to "  ")).resolve() shouldBe userInstall
        }

        "when nothing is found the error lists the path tried and every way to fix it" {
            val message = shouldThrow<IllegalStateException> { resolver(executable = emptySet()).resolve() }.message.orEmpty()

            message shouldContain "$userInstall"
            message shouldContain ENV_SERVER_BINARY
            message shouldContain KotlinConfigParser.SERVER_BINARY
        }
    })
