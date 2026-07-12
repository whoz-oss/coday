package io.whozoss.agentos.agentConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpHeaders
import java.util.UUID

/**
 * Unit tests for [AgentConfigController.export].
 *
 * Permission checks are declarative (`@PreAuthorize`) and do not fire in a direct
 * unit-test invocation — only the YAML serialisation logic and service delegation
 * are exercised here.
 */
class AgentConfigControllerExportSpec : StringSpec({

    val service = mockk<AgentConfigService>()
    val agentService = mockk<AgentService>()
    val userService = mockk<UserService>(relaxed = true)
    val permissionService = mockk<PermissionService>(relaxed = true)
    val controller = AgentConfigController(service, agentService, userService, permissionService)

    val namespaceId = UUID.randomUUID()

    fun config(
        id: UUID = UUID.randomUUID(),
        name: String = "my-agent",
        description: String? = null,
        instructions: String? = null,
        modelName: String? = null,
        integrations: Map<String, List<String>?>? = null,
        subAgents: List<String>? = null,
        docs: List<String>? = null,
    ) = AgentConfig(
        metadata = EntityMetadata(id = id),
        namespaceId = namespaceId,
        name = name,
        description = description,
        instructions = instructions,
        modelName = modelName,
        integrations = integrations,
        subAgents = subAgents,
        docs = docs,
        enabled = true,
        advancedExecution = true,
        externalMetadata = mapOf("theme" to "TALENT"),
    )

    beforeTest { clearAllMocks() }

    // -------------------------------------------------------------------------
    // Content-Disposition header
    // -------------------------------------------------------------------------

    "export returns a response with Content-Disposition attachment header" {
        val c = config(name = "my-agent")
        every { service.findById(c.metadata.id) } returns c

        val response = controller.export(c.metadata.id)

        response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"my-agent.yaml\""
    }

    // -------------------------------------------------------------------------
    // Filename sanitisation
    // -------------------------------------------------------------------------

    "export filename sanitises spaces and special chars to hyphens" {
        val c = config(name = "My Agent (v2)")
        every { service.findById(c.metadata.id) } returns c

        val response = controller.export(c.metadata.id)

        response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe "attachment; filename=\"my-agent-v2-.yaml\""
    }

    "export filename is derived from agent name lowercased with non-alphanumeric runs replaced by hyphens" {
        val c = config(name = "Code Reviewer --- 2024")
        every { service.findById(c.metadata.id) } returns c

        val response = controller.export(c.metadata.id)

        response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION) shouldBe
            "attachment; filename=\"code-reviewer-2024.yaml\""
    }

    // -------------------------------------------------------------------------
    // YAML body — included fields
    // -------------------------------------------------------------------------

    "export YAML contains name field" {
        val c = config(name = "coder")
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        // snakeyaml may quote string values; assert on key presence and value presence separately
        body shouldContain "name:"
        body shouldContain "coder"
    }

    "export YAML contains description when present" {
        val c = config(description = "Writes clean code")
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldContain "description:"
        body shouldContain "Writes clean code"
    }

    "export YAML contains instructions when present" {
        val c = config(instructions = "Be thorough and concise.")
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldContain "instructions:"
        body shouldContain "Be thorough and concise."
    }

    "export YAML contains modelName when present" {
        val c = config(modelName = "claude-sonnet-4-5")
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldContain "modelName:"
        body shouldContain "claude-sonnet-4-5"
    }

    "export YAML contains integrations map when present" {
        val c = config(integrations = mapOf("JIRA" to listOf("GetIssue"), "FILES" to null))
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldContain "integrations:"
        body shouldContain "JIRA:"
        body shouldContain "GetIssue"
    }

    "export YAML contains subAgents list when present" {
        val c = config(subAgents = listOf("reviewer", "fixer"))
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldContain "subAgents:"
        body shouldContain "reviewer"
        body shouldContain "fixer"
    }

    "export YAML contains docs list when present" {
        val c = config(docs = listOf("/path/to/guide.md"))
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldContain "docs:"
        body shouldContain "/path/to/guide.md"
    }

    // -------------------------------------------------------------------------
    // YAML body — excluded fields (scope metadata)
    // -------------------------------------------------------------------------

    "export YAML does not contain id" {
        val c = config()
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "id:"
    }

    "export YAML does not contain namespaceId" {
        val c = config()
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "namespaceId:"
    }

    "export YAML does not contain enabled" {
        val c = config()
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "enabled:"
    }

    "export YAML does not contain advancedExecution" {
        val c = config()
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "advancedExecution:"
    }

    "export YAML does not contain externalMetadata" {
        val c = config()
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "externalMetadata:"
    }

    // -------------------------------------------------------------------------
    // YAML body — null/empty fields omitted
    // -------------------------------------------------------------------------

    "export YAML omits description when null" {
        val c = config(description = null)
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "description:"
    }

    "export YAML omits instructions when null" {
        val c = config(instructions = null)
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "instructions:"
    }

    "export YAML omits modelName when null" {
        val c = config(modelName = null)
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "modelName:"
    }

    "export YAML omits integrations when null" {
        val c = config(integrations = null)
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "integrations:"
    }

    "export YAML omits integrations when empty map" {
        val c = config(integrations = emptyMap())
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "integrations:"
    }

    "export YAML omits subAgents when null" {
        val c = config(subAgents = null)
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "subAgents:"
    }

    "export YAML omits subAgents when empty list" {
        val c = config(subAgents = emptyList())
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "subAgents:"
    }

    "export YAML omits docs when null" {
        val c = config(docs = null)
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "docs:"
    }

    "export YAML omits docs when empty list" {
        val c = config(docs = emptyList())
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        body shouldNotContain "docs:"
    }

    // -------------------------------------------------------------------------
    // Minimal export (name only)
    // -------------------------------------------------------------------------

    "export YAML with only name produces minimal output containing just name" {
        val c = config(name = "minimal")
        every { service.findById(c.metadata.id) } returns c

        val body = controller.export(c.metadata.id).body!!

        // snakeyaml may quote the value; assert on key and value separately
        body shouldContain "name:"
        body shouldContain "minimal"
        body shouldNotContain "description:"
        body shouldNotContain "instructions:"
        body shouldNotContain "modelName:"
        body shouldNotContain "integrations:"
        body shouldNotContain "subAgents:"
        body shouldNotContain "docs:"
    }

    // -------------------------------------------------------------------------
    // 404 on missing entity
    // -------------------------------------------------------------------------

    "export throws ResourceNotFoundException when AgentConfig is not found" {
        val id = UUID.randomUUID()
        every { service.findById(id) } returns null

        shouldThrow<ResourceNotFoundException> { controller.export(id) }
    }

    // -------------------------------------------------------------------------
    // Response body is non-null
    // -------------------------------------------------------------------------

    "export response body is non-null for a valid config" {
        val c = config(name = "test-agent", description = "A test")
        every { service.findById(c.metadata.id) } returns c

        val response = controller.export(c.metadata.id)

        response.body shouldBe response.body // non-null assertion done via !! in body checks above
        (response.body != null) shouldBe true
    }
})
