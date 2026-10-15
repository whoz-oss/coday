package io.whozoss.agentos.queryUser

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import java.util.UUID

class QueryUserConfigPolicySpec : StringSpec({

    val policy = QueryUserConfigPolicy()
    val namespaceId = UUID.randomUUID()

    fun config(allowedQuestionTypes: List<String>?): IntegrationConfig {
        val parameters = allowedQuestionTypes?.let {
            jacksonObjectMapper().readTree(
                """{"allowedQuestionTypes": ${it.joinToString(",", "[", "]") { v -> "\"$v\"" }}}""",
            )
        }
        return IntegrationConfig(
            namespaceId = namespaceId,
            name = "QUERY_USER",
            integrationType = QueryUserToolPlugin.INTEGRATION_TYPE,
            parameters = parameters,
        )
    }

    // -------------------------------------------------------------------------
    // supports
    // -------------------------------------------------------------------------

    "supports is true only for QUERY_USER" {
        policy.supports("QUERY_USER") shouldBe true
        policy.supports("JIRA") shouldBe false
    }

    // -------------------------------------------------------------------------
    // validate -- accepted values
    // -------------------------------------------------------------------------

    "validate accepts the three valid question types" {
        shouldNotThrowAny { policy.validate(config(listOf("FREE_TEXT", "SINGLE_CHOICE", "OPEN_CHOICE"))) }
    }

    "validate accepts an absent allowedQuestionTypes parameter" {
        shouldNotThrowAny { policy.validate(config(null)) }
    }

    "validate accepts an empty allowedQuestionTypes array" {
        shouldNotThrowAny { policy.validate(config(emptyList())) }
    }

    // -------------------------------------------------------------------------
    // validate -- rejected values
    // -------------------------------------------------------------------------

    "validate rejects an unknown question type value, naming it and listing valid values" {
        val error = shouldThrow<UnprocessableEntityException> { policy.validate(config(listOf("BOGUS"))) }
        error.message shouldContain "BOGUS"
        error.message shouldContain "FREE_TEXT"
        error.message shouldContain "SINGLE_CHOICE"
        error.message shouldContain "OPEN_CHOICE"
    }

    "validate rejects OAUTH_AUTHORIZE with a dedicated message" {
        val error = shouldThrow<UnprocessableEntityException> { policy.validate(config(listOf("OAUTH_AUTHORIZE"))) }
        error.message shouldContain "OAUTH_AUTHORIZE"
        error.message shouldContain "OAuth"
    }

    "validate rejects a config mixing a valid value with OAUTH_AUTHORIZE" {
        shouldThrow<UnprocessableEntityException> { policy.validate(config(listOf("FREE_TEXT", "OAUTH_AUTHORIZE"))) }
    }

    // -------------------------------------------------------------------------
    // afterSave
    // -------------------------------------------------------------------------

    "afterSave is a no-op" {
        shouldNotThrowAny { policy.afterSave(config(listOf("FREE_TEXT"))) }
    }
})
