package io.whozoss.factory.openapi

import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public, read-only access to the two versioned OpenAPI contracts used by
 * standard AgentOS HTTP_API integrations.
 */
@RestController
@RequestMapping("/api/factory/openapi")
class FactoryOpenApiController {
    @GetMapping("/factory-worker.yaml", produces = [YAML_MEDIA_TYPE])
    fun worker(): ResponseEntity<ClassPathResource> = contract("factory-worker-http-api-submit-step-result.yaml")

    @GetMapping("/factory-workstream.yaml", produces = [YAML_MEDIA_TYPE])
    fun workstream(): ResponseEntity<ClassPathResource> = contract("factory-workstream-http-api.yaml")

    private fun contract(filename: String): ResponseEntity<ClassPathResource> =
        ResponseEntity.ok()
            .contentType(YAML_UTF8)
            .cacheControl(CacheControl.noCache())
            .header("X-Content-Type-Options", "nosniff")
            .body(ClassPathResource("openapi/$filename"))

    private companion object {
        const val YAML_MEDIA_TYPE = "application/yaml"
        val YAML_UTF8: MediaType = MediaType.parseMediaType("$YAML_MEDIA_TYPE;charset=UTF-8")
    }
}
