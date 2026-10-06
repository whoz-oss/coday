package io.whozoss.agentos.plugins.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.whozoss.agentos.git.core.GitHubApi
import io.whozoss.agentos.sdk.credential.Credential
import io.whozoss.agentos.sdk.credential.CredentialType
import java.util.UUID

class GitForgeAccessSpec :
    StringSpec({
        fun credential(type: CredentialType, data: Map<String, String>) =
            Credential(userId = UUID.randomUUID(), authSettingId = UUID.randomUUID(), credentialType = type, data = data)

        fun access(credential: Credential?) = GitForgeAccess(credential?.let { { it } }, "dev@example.com", GitHubApi())

        "the credential handed to Git and to the forge never prints its secret" {
            val token = access(credential(CredentialType.BEARER_TOKEN, mapOf("token" to "synthetic-user-token"))).token()

            token.secret shouldBe "synthetic-user-token"
            token.toString() shouldNotContain "synthetic-user-token"
        }
    })
