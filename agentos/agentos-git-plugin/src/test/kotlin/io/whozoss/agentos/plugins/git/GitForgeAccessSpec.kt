package io.whozoss.agentos.plugins.git

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.net.http.HttpResponse
import java.util.UUID

class GitForgeAccessSpec :
    StringSpec({
        fun credential(type: CredentialType, data: Map<String, String>) =
            Credential(
                userId = UUID.randomUUID(),
                authSettingId = UUID.randomUUID(),
                credentialType = type,
                data = data,
            )

        fun bearer(token: String = "synthetic-user-token") =
            credential(CredentialType.BEARER_TOKEN, mapOf("token" to token))

        fun access(
            credential: Credential?,
            userExternalId: String? = "dev@example.com",
            gitHub: GitHubApi = GitHubApi(),
        ) = GitForgeAccess(credential?.let { { it } }, userExternalId, gitHub)

        /** A GitHub client whose every call is answered with [status] and [body]. */
        fun gitHubAnswering(status: Int, body: String?): GitHubApi {
            val response = mockk<HttpResponse<String>> {
                every { statusCode() } returns status
                every { body() } returns body
            }
            return GitHubApi(mockk { every { send(any(), any<HttpResponse.BodyHandler<String>>()) } returns response })
        }

        "the credential handed to Git and to the forge never prints its secret" {
            val token = access(bearer()).token()

            token.secret shouldBe "synthetic-user-token"
            token.toString() shouldNotContain "synthetic-user-token"
        }

        "each credential type hands its own secret to Git" {
            val tokenUser = "x-access-token"
            forAll(
                row(CredentialType.OAUTH_TOKENS, mapOf("accessToken" to "oauth-secret"), tokenUser, "oauth-secret"),
                row(CredentialType.BEARER_TOKEN, mapOf("token" to "bearer-secret"), tokenUser, "bearer-secret"),
                row(CredentialType.API_KEY, mapOf("key" to "api-secret"), tokenUser, "api-secret"),
                row(
                    CredentialType.BASIC_AUTH,
                    mapOf("username" to "dev", "password" to "basic-secret"),
                    "dev",
                    "basic-secret",
                ),
                row(CredentialType.BASIC_AUTH, mapOf("password" to "basic-secret"), tokenUser, "basic-secret"),
            ) { type, data, username, secret ->
                val token = access(credential(type, data)).token()

                token.username shouldBe username
                token.secret shouldBe secret
            }
        }

        "an unusable credential is refused before it reaches Git" {
            forAll(
                row("a blank secret", bearer("   ")),
                row("a missing secret", credential(CredentialType.API_KEY, mapOf("token" to "misplaced"))),
                row("a line break in the secret", bearer("synthetic\ntoken")),
                row(
                    "a control character in the username",
                    credential(CredentialType.BASIC_AUTH, mapOf("username" to "dev\r", "password" to "basic-secret")),
                ),
            ) { _, unusable ->
                shouldThrow<GitToolException> { access(unusable).token() }.message shouldBe
                    "Your Git credentials cannot be used for Git over HTTPS"
            }
        }

        "without a credential the user is told to bind their own" {
            shouldThrow<GitToolException> { access(null).token() }.message!! shouldStartWith "No Git credentials"
        }

        "on GitHub an account the API does not return is refused, never guessed" {
            val refused = "GitHub did not return your account"
            forAll(
                row(gitHubAnswering(401, """{"message": "Bad credentials"}"""), "$refused (HTTP 401)"),
                row(gitHubAnswering(200, null), "$refused (HTTP 200)"),
                row(gitHubAnswering(200, """{"id": 42, "name": "Octo Cat"}"""), refused),
                row(gitHubAnswering(200, """{"login": "octocat"}"""), refused),
            ) { gitHub, message ->
                shouldThrow<GitToolException> {
                    access(bearer(), gitHub = gitHub).identity("https://github.com/acme/repo.git")
                }.message shouldBe message
            }
        }

        "outside GitHub the author is the user's email, and a user without one cannot commit" {
            val gitLab = "https://gitlab.example.com/acme/repo.git"

            access(bearer(), "dev@example.com").identity(gitLab) shouldBe
                GitForgeAccess.Identity("dev@example.com", "dev@example.com")
            forAll(
                row<String?>(null),
                row("dev-without-email"),
                row("dev@example.com\nInjected: header"),
            ) { externalId ->
                shouldThrow<GitToolException> { access(bearer(), externalId).identity(gitLab) }.message shouldBe
                    "Cannot determine the commit author: your account has no email address"
            }
        }
    })
