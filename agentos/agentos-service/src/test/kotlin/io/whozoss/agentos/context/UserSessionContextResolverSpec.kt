package io.whozoss.agentos.context

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import java.util.UUID

/**
 * Unit tests for [UserSessionContextResolver].
 *
 * Verifies all branches of [UserSessionContextResolver.resolve] and
 * [UserSessionContextResolver.mergePreferredLanguage] without Spring context.
 */
class UserSessionContextResolverSpec : StringSpec({

    val namespaceId: UUID = UUID.randomUUID()
    val userExternalId = "user@example.com"

    // -------------------------------------------------------------------------
    // resolve() — without provider
    // -------------------------------------------------------------------------

    "resolve returns Success(null) when no provider is registered" {
        val resolver = UserSessionContextResolver()

        val result = resolver.resolve(userExternalId, namespaceId)

        val success = result.shouldBeInstanceOf<UserContextResult.Success>()
        success.sessionContext.shouldBeNull()
    }

    // -------------------------------------------------------------------------
    // resolve() — with provider
    // -------------------------------------------------------------------------

    "resolve returns TransientFailure when provider throws unexpectedly" {
        val provider = mockk<UserContextProvider>().also {
            every { it.provideUserContext(userExternalId, namespaceId) } throws RuntimeException("connection reset")
        }
        val resolver = UserSessionContextResolver(provider)

        val result = resolver.resolve(userExternalId, namespaceId)

        result.shouldBeInstanceOf<UserContextResult.TransientFailure>()
    }

    "resolve returns PermanentFailure when provider returns PermanentFailure" {
        val provider = mockk<UserContextProvider>().also {
            every { it.provideUserContext(userExternalId, namespaceId) } returns
                UserContextResult.PermanentFailure("user not found in external system")
        }
        val resolver = UserSessionContextResolver(provider)

        val result = resolver.resolve(userExternalId, namespaceId)

        val failure = result.shouldBeInstanceOf<UserContextResult.PermanentFailure>()
        failure.reason shouldBe "user not found in external system"
    }

    "resolve returns TransientFailure when provider returns TransientFailure" {
        val provider = mockk<UserContextProvider>().also {
            every { it.provideUserContext(userExternalId, namespaceId) } returns
                UserContextResult.TransientFailure("upstream timeout")
        }
        val resolver = UserSessionContextResolver(provider)

        val result = resolver.resolve(userExternalId, namespaceId)

        val failure = result.shouldBeInstanceOf<UserContextResult.TransientFailure>()
        failure.reason shouldBe "upstream timeout"
    }

    "resolve returns Success with context when provider returns Success" {
        val context = mapOf("talentId" to "t42", "score" to 9.5)
        val provider = mockk<UserContextProvider>().also {
            every { it.provideUserContext(userExternalId, namespaceId) } returns
                UserContextResult.Success(context)
        }
        val resolver = UserSessionContextResolver(provider)

        val result = resolver.resolve(userExternalId, namespaceId)

        val success = result.shouldBeInstanceOf<UserContextResult.Success>()
        success.sessionContext shouldBe context
    }

    // -------------------------------------------------------------------------
    // mergePreferredLanguage()
    // -------------------------------------------------------------------------

    "mergePreferredLanguage returns providerContext unchanged when preferredLanguage is null" {
        val resolver = UserSessionContextResolver()
        val ctx = mapOf("key" to "value")

        val result = resolver.mergePreferredLanguage(ctx, null)

        result shouldBe ctx
    }

    "mergePreferredLanguage returns map with preferredLanguage when providerContext is null" {
        val resolver = UserSessionContextResolver()

        val result = resolver.mergePreferredLanguage(null, "fr")

        result shouldBe mapOf(SessionContextKeys.PREFERRED_LANGUAGE to "fr")
    }

    "mergePreferredLanguage user preferredLanguage wins over provider value on key conflict" {
        val resolver = UserSessionContextResolver()
        // Provider also sends 'preferredLanguage' with a different value
        val ctx = mapOf(SessionContextKeys.PREFERRED_LANGUAGE to "de", "talentId" to "t1")

        val result = resolver.mergePreferredLanguage(ctx, "fr")

        result!![SessionContextKeys.PREFERRED_LANGUAGE] shouldBe "fr"
        result["talentId"] shouldBe "t1"
    }

    "mergePreferredLanguage returns null when both providerContext and preferredLanguage are null" {
        val resolver = UserSessionContextResolver()

        val result = resolver.mergePreferredLanguage(null, null)

        result.shouldBeNull()
    }
})
