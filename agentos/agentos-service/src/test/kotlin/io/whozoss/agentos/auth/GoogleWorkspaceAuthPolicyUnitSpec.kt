package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.authSetting.*

class GoogleWorkspaceAuthPolicyUnitSpec : StringSpec({
    "Google token client never follows redirects or retries" {
        val client = GoogleWorkspaceAuthPolicy.tokenClient(okhttp3.OkHttpClient(), "https://oauth2.googleapis.com/token")
        client.followRedirects shouldBe false
        client.followSslRedirects shouldBe false
        client.retryOnConnectionFailure shouldBe false
    }
    "only registered Google endpoints and read-only scopes are accepted" {
        GoogleWorkspaceAuthPolicy.allowed(OAuthRegisteredAuthSetting(name = "GOOGLE", clientId = "client",
            authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth", tokenUrl = "https://oauth2.googleapis.com/token",
            scopes = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/calendar.readonly https://www.googleapis.com/auth/gmail.readonly")) shouldBe true
    }
    "arbitrary token endpoints never receive governed Google credential refresh" {
        GoogleWorkspaceAuthPolicy.allowed(OAuthRegisteredAuthSetting(name = "GOOGLE", clientId = "client",
            authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth", tokenUrl = "https://evil.test/token",
            scopes = "https://www.googleapis.com/auth/drive.readonly")) shouldBe false
    }
    "broad scopes and discovery and static auth are refused" {
        GoogleWorkspaceAuthPolicy.allowed(OAuthRegisteredAuthSetting(name = "GOOGLE", clientId = "client",
            authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth", tokenUrl = "https://oauth2.googleapis.com/token",
            scopes = "https://www.googleapis.com/auth/drive")) shouldBe false
        GoogleWorkspaceAuthPolicy.allowed(OAuthDiscoverableAuthSetting(name = "GOOGLE")) shouldBe false
        GoogleWorkspaceAuthPolicy.allowed(BearerTokenAuthSetting(name = "GOOGLE")) shouldBe false
    }
})
