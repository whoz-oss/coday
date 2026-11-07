package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.api.aiProvider.AiProviderDto
import io.whozoss.agentos.sdk.api.configChange.ConfigKind
import java.time.Instant
import java.util.UUID

/** What the config agent does with AI providers and AI models: secrets, plan order, immutable and server-resolved fields. */
class ConfigAiProviderUnitSpec :
    StringSpec({
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val callerId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val context =
            ProposalContext(
                callerId = callerId,
                namespaceId = namespaceId,
                caseId = UUID.randomUUID(),
                agentName = "config",
                questionId = UUID.randomUUID(),
                now = Instant.EPOCH,
            )

        val readService = mockk<ConfigReadService>()
        val planner = ConfigPlanner(readService, mapper)

        val providerId = UUID.randomUUID()
        val provider =
            ConfigEntity(
                kind = ConfigKind.AI_PROVIDER,
                id = providerId,
                name = "acme",
                namespaceId = namespaceId,
                userId = null,
                dto = mapper.valueToTree(AiProviderDto(id = providerId, namespaceId = namespaceId, name = "acme", apiType = AiApiType.OpenAI)),
            )
        val agentId = UUID.randomUUID()
        val agent =
            ConfigEntity(
                kind = ConfigKind.AGENT,
                id = agentId,
                name = "dev",
                namespaceId = namespaceId,
                userId = null,
                dto = mapper.readTree("""{"name":"dev","modelName":"SMALL"}"""),
            )
        every { readService.find(ConfigKind.AI_PROVIDER, providerId, callerId, namespaceId) } returns provider
        every { readService.find(ConfigKind.AGENT, agentId, callerId, namespaceId) } returns agent
        every { readService.masked(any()) } answers { ConfigSecretMasker.mask(firstArg<ConfigEntity>().kind, firstArg<ConfigEntity>().dto) }

        fun plan(json: String): PlanningResult = planner.plan(mapper.readValue<ProposeInput>(json), context)

        fun PlanningResult.errors(): List<String> = shouldBeInstanceOf<PlanningResult.Invalid>().errors

        "a provider read masks its apiKey and the credential headers, and only those" {
            val dto =
                mapper.valueToTree<JsonNode>(
                    AiProviderDto(
                        name = "acme",
                        apiType = AiApiType.OpenAI,
                        apiKey = "sk-ant-api03-abcdef-ghijkl",
                        headers = mapOf("Authorization" to "Bearer abc", "X-Api-Key" to "k", "X-Team" to "blue"),
                    ),
                )

            val masked = ConfigSecretMasker.mask(ConfigKind.AI_PROVIDER, dto)

            masked["apiKey"].asText() shouldBe ConfigJson.MASK
            masked["headers"]["Authorization"].asText() shouldBe ConfigJson.MASK
            masked["headers"]["X-Api-Key"].asText() shouldBe ConfigJson.MASK
            masked["headers"]["X-Team"].asText() shouldBe "blue"
            masked.toString() shouldNotContain "sk-ant"
        }

        "a plan creating a provider and a model on it orders them before the agent update that uses the model" {
            val result =
                plan(
                    """
                    { "operations": [
                        { "ref": "dev", "kind": "AGENT", "action": "UPDATE", "id": "$agentId",
                          "changes": [ { "path": "/modelName", "value": "BIG" } ] },
                        { "ref": "model", "kind": "AI_MODEL", "action": "CREATE",
                          "value": { "aiProviderId": { "${'$'}op": "prov" }, "apiModelName": "gpt-x", "alias": "BIG" } },
                        { "ref": "prov", "kind": "AI_PROVIDER", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "acme", "apiType": "OpenAI" },
                          "secrets": [ { "path": "/apiKey", "label": "Acme key" } ] } ] }
                    """.trimIndent(),
                )

            val planned = result.shouldBeInstanceOf<PlanningResult.Planned>().plan
            planned.order shouldContainExactly listOf("prov", "model", "dev")
            planned.operation("prov")!!.secrets.map { it.path } shouldContainExactly listOf("/apiKey")
            planned.operation("model")!!.dependsOn shouldContainExactly listOf("prov")
        }

        "a clear-text apiKey, or a clear-text credential header, is refused" {
            val errors =
                plan(
                    """
                    { "operations": [
                        { "ref": "prov", "kind": "AI_PROVIDER", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "acme", "apiType": "OpenAI", "apiKey": "sk-live-1",
                                     "headers": { "Authorization": "Bearer x", "X-Team": "blue" } } } ] }
                    """.trimIndent(),
                ).errors()

            errors.any { it.contains("'/apiKey' holds a secret") } shouldBe true
            errors.any { it.contains("'/headers/Authorization' holds a secret") } shouldBe true
            errors.none { it.contains("X-Team") } shouldBe true
        }

        "a header credential is declared as a secret path, a plain field is not" {
            val declared =
                plan(
                    """
                    { "operations": [
                        { "ref": "prov", "kind": "AI_PROVIDER", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "acme", "apiType": "OpenAI" },
                          "secrets": [ { "path": "/headers/Authorization" } ] } ] }
                    """.trimIndent(),
                )
            declared.shouldBeInstanceOf<PlanningResult.Planned>()

            val refused =
                plan(
                    """
                    { "operations": [
                        { "ref": "prov", "kind": "AI_PROVIDER", "action": "CREATE", "scope": { "namespaceId": "current" },
                          "value": { "name": "acme", "apiType": "OpenAI" },
                          "secrets": [ { "path": "/baseUrl" } ] } ] }
                    """.trimIndent(),
                ).errors()
            refused.any { it.contains("'/baseUrl' cannot hold a secret for a ai_provider") } shouldBe true
        }

        "changing the apiType of a provider is refused" {
            val errors =
                plan(
                    """
                    { "operations": [
                        { "ref": "prov", "kind": "AI_PROVIDER", "action": "UPDATE", "id": "$providerId",
                          "changes": [ { "path": "/apiType", "value": "Anthropic" }, { "path": "/baseUrl", "value": "https://llm.example.com" } ] } ] }
                    """.trimIndent(),
                ).errors()

            errors.single() shouldContain "'/apiType' cannot be changed after creation"
        }

        "a model carrying its own scope is refused: it takes the scope of its provider" {
            val errors =
                plan(
                    """
                    { "operations": [
                        { "ref": "model", "kind": "AI_MODEL", "action": "CREATE", "scope": { "namespaceId": "current", "userId": "me" },
                          "value": { "aiProviderId": "$providerId", "apiModelName": "gpt-x", "namespaceId": "$namespaceId", "userId": "$callerId" } } ] }
                    """.trimIndent(),
                ).errors()

            errors.any { it.contains("an AI model has no 'scope'") } shouldBe true
            errors.any { it.contains("'value.namespaceId' is not allowed") && it.contains("comes from its AI provider") } shouldBe true
            errors.any { it.contains("'value.userId' is not allowed") } shouldBe true
        }

        "a model needs a provider the user can see, or one the plan creates" {
            val unknown = UUID.randomUUID()
            every { readService.find(ConfigKind.AI_PROVIDER, unknown, callerId, namespaceId) } returns null

            fun errorsOf(value: String) =
                plan("""{ "operations": [ { "ref": "m", "kind": "AI_MODEL", "action": "CREATE", "value": $value } ] }""").errors()

            errorsOf("""{ "apiModelName": "gpt-x" }""").single() shouldContain "needs 'value.aiProviderId'"
            errorsOf("""{ "apiModelName": "gpt-x", "aiProviderId": "$unknown" }""").single() shouldContain "no ai_provider with id $unknown"
            errorsOf("""{ "apiModelName": "gpt-x", "aiProviderId": { "${'$'}op": "nope" } }""").single() shouldContain "unknown reference"
            plan("""{ "operations": [ { "ref": "m", "kind": "AI_MODEL", "action": "CREATE", "value": { "apiModelName": "gpt-x", "aiProviderId": "$providerId" } } ] }""")
                .shouldBeInstanceOf<PlanningResult.Planned>()
        }

        "enabling, disabling or deploying a provider stays refused: those actions are for agents" {
            plan("""{ "operations": [ { "kind": "AI_PROVIDER", "action": "ENABLE", "id": "$providerId" } ] }""")
                .errors()
                .single() shouldContain "ENABLE is not supported for AI_PROVIDER"
        }
    })
