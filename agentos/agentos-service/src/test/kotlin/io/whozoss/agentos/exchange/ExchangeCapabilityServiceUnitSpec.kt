package io.whozoss.agentos.exchange

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.exchange.ExchangeCapability
import java.util.UUID

/**
 * Unit tests for [ExchangeCapabilityService], the single place where the exchange read and write
 * rules are defined.
 *
 * Each method is a one-line delegation, so what is worth pinning is the [Action] it asks for: the
 * three members differ by that argument alone, and swapping one for another compiles, passes every
 * caller's spec (they all mock this service), and silently changes who can reach the exchange.
 */
class ExchangeCapabilityServiceUnitSpec : StringSpec() {
    private val permissionService: PermissionService = mockk()
    private val service = ExchangeCapabilityService(permissionService)

    private val userId = UUID.randomUUID().toString()
    private val namespaceId = UUID.randomUUID().toString()

    init {
        "canRead asks the permission service for READ, not WRITE" {
            // A copy-paste of the canWrite body would still compile and still pass every caller
            // spec, while denying the namespace exchange to every plain member.
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.WRITE) } returns false

            service.canRead(userId, EntityType.NAMESPACE, namespaceId) shouldBe true
        }

        "canRead is false when the user lacks READ on the scope" {
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.READ) } returns false

            service.canRead(userId, EntityType.NAMESPACE, namespaceId) shouldBe false
        }

        "canWrite asks the permission service for WRITE, not READ" {
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId, Action.WRITE) } returns false

            service.canWrite(userId, EntityType.NAMESPACE, namespaceId) shouldBe false
        }

        "capability upgrades to READ_WRITE only when the user holds WRITE" {
            every { permissionService.hasPermission(userId, EntityType.CASE, namespaceId, Action.WRITE) } returns true

            service.capability(userId, EntityType.CASE, namespaceId) shouldBe ExchangeCapability.READ_WRITE
        }

        "capability stays READ for a caller without WRITE" {
            every { permissionService.hasPermission(userId, EntityType.CASE, namespaceId, Action.WRITE) } returns false

            service.capability(userId, EntityType.CASE, namespaceId) shouldBe ExchangeCapability.READ
        }
    }
}
