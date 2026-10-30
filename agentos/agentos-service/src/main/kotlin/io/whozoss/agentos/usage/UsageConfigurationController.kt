package io.whozoss.agentos.usage

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.config.UsageConfigProperties
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Schema(name = "UsageConfiguration")
data class UsageConfiguration(
    val enabled: Boolean,
)

/** Exposes the deployment setting so clients can avoid disabled usage features. */
@RestController
@RequestMapping("/api/usage-configuration", produces = [MediaType.APPLICATION_JSON_VALUE])
class UsageConfigurationController(
    private val usageConfig: UsageConfigProperties,
) {
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getUsageConfiguration")
    fun getUsageConfiguration(): UsageConfiguration = UsageConfiguration(enabled = usageConfig.enabled)
}
