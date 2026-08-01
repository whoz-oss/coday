package io.whozoss.agentos.prompt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpHeaders
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [PromptController.export].
 *
 * Permission checks are declarative (`@PreAuthorize`) and do not fire in a direct
 * unit-test invocation — only the YAML serialisation logic and service delegation
 * are exercised here.
 */
class PromptControllerExportSpec : StringSpec({

    val promptService = mockk<PromptService>()
    val namespaceService = mockk<NamespaceService>(relaxed = true)
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = PromptController(promptService, namespaceService, userService, permissionService)

    val namespaceId = UUID.randomUUID()

    /** A YAML mapper mirroring the structural shape read by FilesystemPromptRepository's
     *  private PromptYamlModel — used only to assert the round-trip in this spec, without
     *  degrading the visibility of the production model. */
    val readerMapper = ObjectMapper(YAMLFactory()).registerModule(KotlinModule.Builder().build())

    data class ParamRoundTrip(
        val name: String = "",
        val description: String? = null,
        val defaultValue: String = "",
    )

    data class PromptRoundTrip(
        val name: String = "",
        val description: String? = null,
        val content: List<String>? = null,
        val parameters: List<ParamRoundTrip>? = null,
    )

    fun prompt(
        id: UUID = UUID.randomUUID(),
        name: String = "my-prompt",
        description: String? = null,
        content: List<String> = listOf("Hello {{name}}"),
        parameters: List<PromptParameter> = emptyList(),
        agentConfigId: UUID? = null,
        userId: UUID? = null,
        externalMetadata: Map<String, Any?>? = null,
    ) = Prompt(
        metadata =
            EntityMetadata(
                id = id,
                created = Instant.parse("2024-01-01T00:00:00Z"),
                createdBy = "alice",
                modified = Instant.parse("2024-06-01T00:00:00Z"),
                modifiedBy = "bob",
            ),
        namespaceId = namespaceId,
        userId = userId,
        agentConfigId = agentConfigId,
        name = name,
        description = description,
        content = content,
        parameters = parameters,
        externalMetadata = externalMetadata,
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Content-Disposition header
    // -------------------------------------------------------------------------

    "export returns a response with Content-Disposition attachment header" {
        val p = prompt(name = "my-prompt")
        every { promptService.findById(p.metadata.id) } returns p

        val response = controller.export(p.metadata.id)

        response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"my-prompt.yaml\""
    }

    // -------------------------------------------------------------------------
    // Filename sanitisation
    // -------------------------------------------------------------------------

    "export filename sanitises spaces and special chars to hyphens" {
        val p = prompt(name = "My Prompt (v2)")
        every { promptService.findById(p.metadata.id) } returns p

        val response = controller.export(p.metadata.id)

        response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"my-prompt-v2-.yaml\""
    }

    "export filename is derived from prompt name lowercased with non-alphanumeric runs replaced by hyphens" {
        val p = prompt(name = "Code Review --- 2024")
        every { promptService.findById(p.metadata.id) } returns p

        val response = controller.export(p.metadata.id)

        response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe
            "attachment; filename=\"code-review-2024.yaml\""
    }

    // -------------------------------------------------------------------------
    // YAML body — included fields, always present
    // -------------------------------------------------------------------------

    "export YAML contains name field" {
        val p = prompt(name = "planner")
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "name:"
        body shouldContain "planner"
    }

    "export YAML always contains content, single line" {
        val p = prompt(content = listOf("Single line prompt"))
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "content:"
        body shouldContain "Single line prompt"
    }

    "export YAML preserves multi-line content in order" {
        val p = prompt(content = listOf("Line one", "Line two", "Line three"))
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        val idx1 = body.indexOf("Line one")
        val idx2 = body.indexOf("Line two")
        val idx3 = body.indexOf("Line three")
        (idx1 in 0 until idx2) shouldBe true
        (idx2 in 0 until idx3) shouldBe true
    }

    // -------------------------------------------------------------------------
    // YAML body — description
    // -------------------------------------------------------------------------

    "export YAML contains description when present" {
        val p = prompt(description = "Creates an implementation plan")
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "description:"
        body shouldContain "Creates an implementation plan"
    }

    "export YAML omits description when null" {
        val p = prompt(description = null)
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldNotContain "description:"
    }

    "export YAML omits description when blank" {
        val p = prompt(description = "   ")
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldNotContain "description:"
    }

    // -------------------------------------------------------------------------
    // YAML body — parameters
    // -------------------------------------------------------------------------

    "export YAML contains parameters with name, description and defaultValue" {
        val p =
            prompt(
                parameters =
                    listOf(
                        PromptParameter(name = "scope", description = "what to plan", defaultValue = "backend"),
                    ),
            )
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "parameters:"
        body shouldContain "scope"
        body shouldContain "what to plan"
        body shouldContain "backend"
    }

    "export YAML omits parameter description when null" {
        val p =
            prompt(
                parameters = listOf(PromptParameter(name = "scope", description = null, defaultValue = "backend")),
            )
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "parameters:"
        body shouldContain "scope"
        body shouldNotContain "description:"
    }

    "export YAML preserves an empty string defaultValue" {
        val p =
            prompt(
                parameters = listOf(PromptParameter(name = "freeform", defaultValue = "")),
            )
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "parameters:"
        body shouldContain "freeform"
        body shouldContain "defaultValue:"

        // Round-trip through a reader mapper: the parameter must survive with
        // defaultValue == "", not be omitted or coerced to null.
        val parsed = readerMapper.readValue(body, PromptRoundTrip::class.java)
        parsed.parameters.shouldNotBeNull()
        val param = parsed.parameters.first { it.name == "freeform" }
        param.defaultValue shouldBe ""
    }

    "export YAML omits parameters block when list is empty" {
        val p = prompt(parameters = emptyList())
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldNotContain "parameters:"
    }

    // -------------------------------------------------------------------------
    // YAML body — excluded fields (persistence artefacts)
    // -------------------------------------------------------------------------

    "export YAML excludes id, namespaceId, userId, agentConfigId, externalMetadata and audit fields" {
        val p =
            prompt(
                agentConfigId = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                externalMetadata = mapOf("label" to "Starter", "triggers" to listOf("go")),
            )
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldNotContain "id:"
        body shouldNotContain "namespaceId:"
        body shouldNotContain "userId:"
        body shouldNotContain "agentConfigId:"
        body shouldNotContain "externalMetadata:"
        body shouldNotContain "createdBy:"
        body shouldNotContain "createdOn:"
        body shouldNotContain "created:"
        body shouldNotContain "updatedBy:"
        body shouldNotContain "updatedOn:"
        body shouldNotContain "modified:"
        body shouldNotContain "removed:"
        body shouldNotContain "version:"
        body shouldNotContain "alice"
        body shouldNotContain "bob"
        body shouldNotContain "Starter"
    }

    // -------------------------------------------------------------------------
    // Minimal export (name + content only)
    // -------------------------------------------------------------------------

    "export YAML with only name and content produces minimal output" {
        val p = prompt(name = "minimal", description = null, content = listOf("just this"), parameters = emptyList())
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!

        body shouldContain "name:"
        body shouldContain "minimal"
        body shouldContain "content:"
        body shouldContain "just this"
        body shouldNotContain "description:"
        body shouldNotContain "parameters:"
    }

    // -------------------------------------------------------------------------
    // 404 on missing entity
    // -------------------------------------------------------------------------

    "export throws ResourceNotFoundException when Prompt is not found" {
        val id = UUID.randomUUID()
        every { promptService.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.export(id) }
    }

    // -------------------------------------------------------------------------
    // Round-trip: exported YAML is re-readable in the loader's shape
    // -------------------------------------------------------------------------

    "export YAML round-trips into a structure equivalent to the filesystem loader's model" {
        val p =
            prompt(
                name = "Full Prompt",
                description = "A complete example",
                content = listOf("Analyse: {{ARGUMENTS}}", "Focus on {{scope}}"),
                parameters =
                    listOf(
                        PromptParameter(name = "scope", description = "what to plan", defaultValue = ""),
                        PromptParameter(name = "language", description = null, defaultValue = "English"),
                    ),
                agentConfigId = UUID.randomUUID(),
                externalMetadata = mapOf("label" to "Starter"),
            )
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!
        val parsed = readerMapper.readValue(body, PromptRoundTrip::class.java)

        parsed.name shouldBe "Full Prompt"
        parsed.description shouldBe "A complete example"
        parsed.content shouldBe listOf("Analyse: {{ARGUMENTS}}", "Focus on {{scope}}")
        parsed.parameters.shouldNotBeNull()
        parsed.parameters shouldHaveSize 2

        val scope = parsed.parameters.first { it.name == "scope" }
        scope.description shouldBe "what to plan"
        scope.defaultValue shouldBe ""

        val language = parsed.parameters.first { it.name == "language" }
        language.description shouldBe null
        language.defaultValue shouldBe "English"
    }

    "export YAML round-trip with no parameters yields an empty or absent parameters block" {
        val p = prompt(name = "bare", content = listOf("just content"), parameters = emptyList())
        every { promptService.findById(p.metadata.id) } returns p

        val body = controller.export(p.metadata.id).body!!
        val parsed = readerMapper.readValue(body, PromptRoundTrip::class.java)

        parsed.name shouldBe "bare"
        parsed.content shouldBe listOf("just content")
        parsed.parameters.isNullOrEmpty() shouldBe true
    }
})
