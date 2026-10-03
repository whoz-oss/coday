package io.whozoss.agentos.binding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.spi.ExternalContextBindingRegistrar
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.pf4j.PluginManager
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Host transport wiring for external execution bindings.
 *
 * Verifies that the `X-Factory-*` headers (and the shared credential) received on case
 * creation, or on the dedicated binding endpoint, are forwarded to the loaded
 * [ExternalContextBindingRegistrar] — the plugin side of the bridge — and that the
 * transport is fail-closed when no registrar accepts.
 */
class ExternalContextBindingSpec : StringSpec({
    val token = "secret-token-value-with-sufficient-length"

    fun pluginManager(registrars: List<ExternalContextBindingRegistrar>): PluginManager =
        mockk<PluginManager>(relaxed = true).also {
            every { it.getExtensions(ExternalContextBindingRegistrar::class.java) } returns registrars
        }

    fun caseService(
        caseId: UUID,
        namespaceId: UUID,
    ): CaseService =
        mockk<CaseService>().also {
            every { it.findById(caseId, false) } returns Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
        }

    "the binding endpoint forwards X-Factory-* headers to the registrar" {
        val caseId = UUID.randomUUID()
        val namespaceId = UUID.randomUUID()
        val registrar = RecordingBindingRegistrar()
        val controller = ExternalContextBindingController(caseService(caseId, namespaceId), pluginManager(listOf(registrar)))

        controller.bind(caseId, "shared-secret", null, "attempt", token, "runtime", "Worker", null)

        val call = registrar.calls.single()
        call.caseId shouldBe caseId
        call.namespaceId shouldBe namespaceId
        call.credential shouldBe "shared-secret"
        call.attributes["attemptId"] shouldBe "attempt"
        call.attributes["capabilityToken"] shouldBe token
        call.attributes["runtimeId"] shouldBe "runtime"
        call.attributes["agentName"] shouldBe "Worker"
    }

    "the binding endpoint fails closed when no registrar is loaded" {
        val caseId = UUID.randomUUID()
        val controller = ExternalContextBindingController(caseService(caseId, UUID.randomUUID()), pluginManager(emptyList()))

        val error = shouldThrow<ResponseStatusException> {
            controller.bind(caseId, "shared-secret", null, "attempt", token, "runtime", "Worker", null)
        }
        error.statusCode shouldBe HttpStatus.NOT_FOUND
    }

    "the binding endpoint rejects with 401 when every registrar refuses" {
        val caseId = UUID.randomUUID()
        val controller =
            ExternalContextBindingController(
                caseService(caseId, UUID.randomUUID()),
                pluginManager(listOf(RecordingBindingRegistrar(accept = false))),
            )

        val error = shouldThrow<ResponseStatusException> {
            controller.bind(caseId, "wrong-secret", null, "attempt", token, "runtime", "Worker", null)
        }
        error.statusCode shouldBe HttpStatus.UNAUTHORIZED
    }

    "a case created with X-Factory-* headers is bound from the created-case response" {
        val namespaceId = UUID.randomUUID()
        val registrar = RecordingBindingRegistrar()
        val filter = ExternalContextBindingFilter(pluginManager(listOf(registrar)), jacksonObjectMapper())
        val caseId = UUID.randomUUID()

        val request = MockHttpServletRequest("POST", "/api/cases").apply {
            addHeader("X-Factory-Attempt-Id", "attempt")
            addHeader("X-Factory-Capability-Token", token)
            addHeader("X-Factory-Agentos-Secret", "shared-secret")
        }
        val response = MockHttpServletResponse()
        val body = """{"id":"$caseId","namespaceId":"$namespaceId"}"""

        filter.doFilter(
            request,
            response,
            FilterChain { _, res ->
                (res as HttpServletResponse).status = 201
                res.writer.write(body)
            },
        )

        // The buffered response body is forwarded to the client unchanged.
        response.status shouldBe 201
        response.contentAsString shouldBe body

        val call = registrar.calls.single()
        call.caseId shouldBe caseId
        call.namespaceId shouldBe namespaceId
        call.credential shouldBe "shared-secret"
        call.attributes["attemptId"] shouldBe "attempt"
        call.attributes["capabilityToken"] shouldBe token
    }

    "case creation without binding headers is a strict no-op" {
        val registrar = RecordingBindingRegistrar()
        val filter = ExternalContextBindingFilter(pluginManager(listOf(registrar)), jacksonObjectMapper())

        val request = MockHttpServletRequest("POST", "/api/cases")
        val response = MockHttpServletResponse()

        filter.doFilter(
            request,
            response,
            FilterChain { _, res ->
                (res as HttpServletResponse).status = 201
                res.writer.write("""{"id":"${UUID.randomUUID()}","namespaceId":"${UUID.randomUUID()}"}""")
            },
        )

        registrar.calls shouldBe emptyList()
        response.status shouldBe 201
    }
})

/** Test double recording the bindings forwarded by the host transport. */
internal class RecordingBindingRegistrar(
    private val accept: Boolean = true,
) : ExternalContextBindingRegistrar {
    data class Call(
        val caseId: UUID,
        val namespaceId: UUID,
        val credential: String?,
        val attributes: Map<String, String>,
        val expiresAt: Instant?,
    )

    val calls = mutableListOf<Call>()

    override fun register(
        caseId: UUID,
        namespaceId: UUID,
        credential: String?,
        attributes: Map<String, String>,
        expiresAt: Instant?,
    ): Boolean {
        calls += Call(caseId, namespaceId, credential, attributes, expiresAt)
        return accept
    }
}

private fun jacksonObjectMapper() = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
