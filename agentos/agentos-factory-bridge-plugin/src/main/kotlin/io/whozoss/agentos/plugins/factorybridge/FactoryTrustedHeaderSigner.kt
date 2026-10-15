package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.tool.ToolContext
import okhttp3.Request
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FactoryTrustedHeaderSigner(
    private val secret: String?,
    private val serviceIdentityId: String,
    private val scopes: List<String>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun sign(builder: Request.Builder, context: ToolContext): Result<Request.Builder> {
        val configuredSecret = secret?.takeIf(String::isNotBlank)
            ?: return Result.failure(IllegalStateException("Factory bridge signing secret is not configured"))
        val caseId = context.caseEvents.map { it.caseId }.distinct().singleOrNull()
            ?: return Result.failure(IllegalStateException("A single controlling case is required"))
        val actor = context.userExternalId?.takeIf(String::isNotBlank) ?: context.userId?.toString()
            ?: return Result.failure(IllegalStateException("A controlling actor is required"))
        if (serviceIdentityId.isBlank()) return Result.failure(IllegalStateException("Service identity is not configured"))

        val headers = linkedMapOf(
            "x-proxy-principal-id" to actor,
            "x-proxy-principal-type" to "service",
            "x-proxy-service-identity-id" to serviceIdentityId,
            "x-proxy-scopes" to scopes.joinToString(","),
            "x-proxy-namespace-id" to context.namespaceId.toString(),
            "x-proxy-case-id" to caseId.toString(),
            "x-proxy-timestamp" to now().toString(),
        )
        val payload = headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(configuredSecret.toByteArray(), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("x-proxy-signature", signature)
        return Result.success(builder)
    }
}
