package io.whozoss.agentos.sdk.api.case

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid

@Schema(name = "CaseShareRequest")
@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseShareRequest(
    @field:Valid
    val entries: List<CaseShareEntry>,
)
