package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.Request
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Shared signer for trusted AgentOS → Factory Workstream write requests. */
class FactoryTrustedHeaderSigner(
    private val secret: String?,
    private val serviceIdentityId: String,
    private val scopes: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun sign(
        builder: Request.Builder,
        context: ToolContext,
    ): Result<Request.Builder> {
        val configuredSecret = secret?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Factory bridge signing secret is not configured."))
        val caseIds = context.caseEvents.map { it.caseId }.distinct()
        if (caseIds.size != 1) return Result.failure(IllegalStateException("A single controlling case is required."))
        val actor = context.userExternalId?.takeIf { it.isNotBlank() } ?: context.userId?.toString()
            ?: return Result.failure(IllegalStateException("A controlling actor is required."))
        if (serviceIdentityId.isBlank()) return Result.failure(IllegalStateException("Factory bridge service identity is not configured."))

        val headers = linkedMapOf(
            PROXY_PRINCIPAL_ID_HEADER to actor,
            PROXY_PRINCIPAL_TYPE_HEADER to PRINCIPAL_TYPE_SERVICE,
            PROXY_SERVICE_IDENTITY_ID_HEADER to serviceIdentityId,
            PROXY_SCOPES_HEADER to scopes.joinToString(","),
            PROXY_NAMESPACE_ID_HEADER to context.namespaceId.toString(),
            PROXY_CASE_ID_HEADER to caseIds.single().toString(),
            PROXY_TIMESTAMP_HEADER to now().toString(),
        ).filterValues { it.isNotEmpty() }
        val payload = SIGNED_HEADERS.filter { headers[it] != null }.joinToString("\n") { "$it:${headers[it]}" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(configuredSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header(PROXY_SIGNATURE_HEADER, signature)
        return Result.success(builder)
    }

    companion object {
        const val PROXY_SIGNATURE_HEADER = "x-proxy-signature"
        const val PROXY_TIMESTAMP_HEADER = "x-proxy-timestamp"
        const val PROXY_PRINCIPAL_ID_HEADER = "x-proxy-principal-id"
        const val PROXY_PRINCIPAL_TYPE_HEADER = "x-proxy-principal-type"
        const val PROXY_SERVICE_IDENTITY_ID_HEADER = "x-proxy-service-identity-id"
        const val PROXY_SCOPES_HEADER = "x-proxy-scopes"
        const val PROXY_NAMESPACE_ID_HEADER = "x-proxy-namespace-id"
        const val PROXY_CASE_ID_HEADER = "x-proxy-case-id"
        const val PRINCIPAL_TYPE_SERVICE = "service"

        private val SIGNED_HEADERS = listOf(
            PROXY_PRINCIPAL_ID_HEADER,
            PROXY_PRINCIPAL_TYPE_HEADER,
            PROXY_SERVICE_IDENTITY_ID_HEADER,
            PROXY_SCOPES_HEADER,
            PROXY_NAMESPACE_ID_HEADER,
            PROXY_CASE_ID_HEADER,
            PROXY_TIMESTAMP_HEADER,
        )
    }
}
