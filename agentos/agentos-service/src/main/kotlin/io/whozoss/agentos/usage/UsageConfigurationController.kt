package io.whozoss.agentos.usage

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.config.UsageConfigProperties
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Schema(name = "UsageConfiguration")
data class UsageConfiguration(
    val enabled: Boolean,
    @field:Schema(
        description =
            "Platform default of the run cost threshold, in the platform currency unit " +
                "(agentos.limits.run-cost-threshold). Inherited by a namespace or case that sets none. " +
                "Null when the platform sets no limit.",
        nullable = true,
    )
    val runCostThreshold: Double? = null,
)

/** Exposes the deployment settings so clients can avoid disabled usage features and show inherited defaults. */
@RestController
@RequestMapping("/api/usage-configuration", produces = [MediaType.APPLICATION_JSON_VALUE])
class UsageConfigurationController(
    private val usageConfig: UsageConfigProperties,
    private val limitsConfig: LimitsConfigProperties = LimitsConfigProperties(),
) {
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "getUsageConfiguration")
    fun getUsageConfiguration(): UsageConfiguration =
        UsageConfiguration(
            enabled = usageConfig.enabled,
            runCostThreshold = limitsConfig.runCostThreshold,
        )
}
