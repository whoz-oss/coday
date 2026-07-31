package io.whozoss.agentos.sdk.api.prompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import java.util.UUID

/**
 * Unit tests for the [PromptSearchRequest] cross-field `@AssertTrue` constraints
 * ([PromptSearchRequest.isNamespaceIdentifierValid] and [PromptSearchRequest.isUserIdentifierValid]).
 *
 * These constraints enforce an exclusive-or between a UUID identifier and its
 * `*ExternalId` counterpart: exactly one of the pair must be provided, never both,
 * never neither.
 *
 * We assert directly on the computed boolean properties rather than instantiating a
 * jakarta.validation Validator, since agentos-sdk only depends on the Validation API
 * as `compileOnly` (no Bean Validation implementation on the test classpath) — see
 * [io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDtoUnitSpec] for the same rationale.
 */
class PromptSearchRequestUnitSpec : StringSpec({

    fun request(
        namespaceId: UUID? = null,
        namespaceExternalId: String? = null,
        userId: UUID? = null,
        userExternalId: String? = null,
    ) = PromptSearchRequest(
        namespaceId = namespaceId,
        namespaceExternalId = namespaceExternalId,
        userId = userId,
        userExternalId = userExternalId,
    )

    // -------------------------------------------------------------------------
    // Namespace identifier
    // -------------------------------------------------------------------------

    "namespace: invalid when both namespaceId and namespaceExternalId are null" {
        request(namespaceId = null, namespaceExternalId = null).isNamespaceIdentifierValid.shouldBeFalse()
    }

    "namespace: valid when only namespaceId is provided" {
        request(namespaceId = UUID.randomUUID(), namespaceExternalId = null).isNamespaceIdentifierValid.shouldBeTrue()
    }

    "namespace: valid when only namespaceExternalId is provided" {
        request(namespaceId = null, namespaceExternalId = "ext-ns").isNamespaceIdentifierValid.shouldBeTrue()
    }

    "namespace: invalid when both namespaceId and namespaceExternalId are provided" {
        request(namespaceId = UUID.randomUUID(), namespaceExternalId = "ext-ns").isNamespaceIdentifierValid.shouldBeFalse()
    }

    // -------------------------------------------------------------------------
    // User identifier
    // -------------------------------------------------------------------------

    "user: invalid when both userId and userExternalId are null" {
        request(userId = null, userExternalId = null).isUserIdentifierValid.shouldBeFalse()
    }

    "user: valid when only userId is provided" {
        request(userId = UUID.randomUUID(), userExternalId = null).isUserIdentifierValid.shouldBeTrue()
    }

    "user: valid when only userExternalId is provided" {
        request(userId = null, userExternalId = "ext-user").isUserIdentifierValid.shouldBeTrue()
    }

    "user: invalid when both userId and userExternalId are provided" {
        request(userId = UUID.randomUUID(), userExternalId = "ext-user").isUserIdentifierValid.shouldBeFalse()
    }
})
