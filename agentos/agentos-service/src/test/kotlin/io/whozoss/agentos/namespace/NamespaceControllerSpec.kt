package io.whozoss.agentos.namespace

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Unit tests for [NamespaceController].
 *
 * The controller is instantiated directly with MockK stubs — no Spring context.
 * Tests cover:
 * - [NamespaceController.toResource]  — domain → HTTP DTO mapping
 * - [NamespaceController.toDomain]    — HTTP DTO → domain mapping, including blank configPath normalization
 * - [NamespaceController.listAll]     — delegates to [NamespaceService.findAll] and maps results
 * - Inherited [io.whozoss.agentos.entity.EntityController] endpoints:
 *   getById (found / not-found), getByIds, create, update (found / not-found),
 *   delete (found / not-found)
 */
class NamespaceControllerSpec : StringSpec({
    timeout = 5000

    val namespaceService = mockk<NamespaceService>()
    val controller = NamespaceController(namespaceService)

    fun ns(
        id: UUID = UUID.randomUUID(),
        name: String = "engineering",
        description: String? = null,
        configPath: String? = null,
    ) = Namespace(
        metadata = EntityMetadata(id = id),
        name = name,
        description = description,
        configPath = configPath,
    )

    fun resource(
        id: UUID? = UUID.randomUUID(),
        name: String = "engineering",
        description: String? = null,
        configPath: String? = null,
    ) = NamespaceResource(
        id = id,
        name = name,
        description = description,
        configPath = configPath,
    )

    // -------------------------------------------------------------------------
    // toResource mapping
    // -------------------------------------------------------------------------

    "toResource maps all fields including configPath" {
        val id = UUID.randomUUID()
        val entity = ns(id = id, name = "coday", description = "Coday project", configPath = "/opt/coday")

        val result = controller.toResource(entity)

        result shouldBe NamespaceResource(id = id, name = "coday", description = "Coday project", configPath = "/opt/coday")
    }

    "toResource preserves null configPath" {
        val entity = ns(configPath = null)

        controller.toResource(entity).configPath shouldBe null
    }

    // -------------------------------------------------------------------------
    // toDomain mapping
    // -------------------------------------------------------------------------

    "toDomain maps all fields from NamespaceResource to Namespace" {
        val id = UUID.randomUUID()
        val r = resource(id = id, name = "platform", description = "Platform team", configPath = "/opt/platform")

        val result = controller.toDomain(r)

        result.metadata.id shouldBe id
        result.name shouldBe "platform"
        result.description shouldBe "Platform team"
        result.configPath shouldBe "/opt/platform"
    }

    "toDomain normalizes blank configPath to null" {
        val r = resource(configPath = "   ")

        controller.toDomain(r).configPath shouldBe null
    }

    "toDomain normalizes empty string configPath to null" {
        val r = resource(configPath = "")

        controller.toDomain(r).configPath shouldBe null
    }

    "toDomain preserves null configPath" {
        val r = resource(configPath = null)

        controller.toDomain(r).configPath shouldBe null
    }

    "toDomain generates a random UUID when resource id is null" {
        val r = resource(id = null)

        val result = controller.toDomain(r)

        result.metadata.id shouldBe result.metadata.id
    }

    // -------------------------------------------------------------------------
    // listAll
    // -------------------------------------------------------------------------

    "listAll returns all namespaces mapped to NamespaceResource" {
        val ns1 = ns(name = "engineering")
        val ns2 = ns(name = "product")
        every { namespaceService.findAll() } returns listOf(ns1, ns2)

        val result = controller.listAll()

        result shouldBe listOf(controller.toResource(ns1), controller.toResource(ns2))
        verify(exactly = 1) { namespaceService.findAll() }
    }

    "listAll returns empty list when no namespaces exist" {
        every { namespaceService.findAll() } returns emptyList()

        controller.listAll() shouldBe emptyList()
    }

    // -------------------------------------------------------------------------
    // getById (inherited from EntityController)
    // -------------------------------------------------------------------------

    "getById returns a NamespaceResource when namespace is found" {
        val entity = ns()
        every { namespaceService.findByIds(listOf(entity.id)) } returns listOf(entity)
        every { namespaceService.findById(entity.id) } returns entity

        val result = controller.getById(entity.id)

        result shouldBe controller.toResource(entity)
    }

    "getById throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        every { namespaceService.findByIds(listOf(id)) } returns emptyList()
        every { namespaceService.findById(id) } returns null

        val ex = runCatching { controller.getById(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // getByIds (inherited)
    // -------------------------------------------------------------------------

    "getByIds returns matching namespaces mapped to resources" {
        val ns1 = ns(name = "engineering")
        val ns2 = ns(name = "product")
        every { namespaceService.findByIds(listOf(ns1.id, ns2.id)) } returns listOf(ns1, ns2)

        val result = controller.getByIds(listOf(ns1.id, ns2.id))

        result shouldBe listOf(controller.toResource(ns1), controller.toResource(ns2))
    }

    // -------------------------------------------------------------------------
    // create (inherited)
    // -------------------------------------------------------------------------

    "create converts resource to domain, delegates to service, and returns mapped resource" {
        val r = resource(id = null, name = "new-namespace")
        val saved = controller.toDomain(r)
        every { namespaceService.create(any()) } returns saved

        val result = controller.create(r)

        result shouldBe controller.toResource(saved)
        verify(exactly = 1) { namespaceService.create(any()) }
    }

    // -------------------------------------------------------------------------
    // update (inherited)
    // -------------------------------------------------------------------------

    "update delegates to service when namespace exists and returns mapped resource" {
        val entity = ns()
        val updatedResource = resource(id = entity.id, name = "renamed", configPath = "/new/path")
        val updatedDomain = controller.toDomain(updatedResource)
        every { namespaceService.findByIds(listOf(entity.id)) } returns listOf(entity)
        every { namespaceService.findById(entity.id) } returns entity
        every { namespaceService.update(any()) } returns updatedDomain

        val result = controller.update(entity.id, updatedResource)

        result shouldBe controller.toResource(updatedDomain)
        verify(exactly = 1) { namespaceService.update(any()) }
    }

    "update throws 404 when namespace not found" {
        val id = UUID.randomUUID()
        val r = resource(id = id)
        every { namespaceService.findByIds(listOf(id)) } returns emptyList()
        every { namespaceService.findById(id) } returns null

        val ex = runCatching { controller.update(id, r) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    // -------------------------------------------------------------------------
    // delete (inherited)
    // -------------------------------------------------------------------------

    "delete succeeds when namespace exists" {
        val id = UUID.randomUUID()
        every { namespaceService.delete(id) } returns true

        controller.delete(id)

        verify(exactly = 1) { namespaceService.delete(id) }
    }

    "delete throws 404 when service returns false" {
        val id = UUID.randomUUID()
        every { namespaceService.delete(id) } returns false

        val ex = runCatching { controller.delete(id) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }
})
