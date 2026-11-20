package io.whozoss.agentos.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun agentOsOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("AgentOS API")
                .description("REST API for AgentOS — orchestration of AI agents via plugins")
                .version("0.0.1"),
        )

    /**
     * Override operationId to produce globally-unique, readable names.
     *
     * By default, springdoc appends numeric suffixes (_1, _2…) when multiple controllers
     * share the same method name (e.g. getById, create).
     *
     * The OpenAPI spec requires operationIds to be globally unique across the entire document,
     * so we build them as "{methodName}{EntityName}" in camelCase — e.g. getByIdUser,
     * createCase, listAllNamespace. This produces clean method names in the generated Angular
     * client while remaining unambiguous globally.
     *
     * Controllers that do not follow the "XxxController" naming convention fall back to
     * the bare method name (which is already unique for non-entity controllers).
     */
    @Bean
    fun methodNameOperationCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            val controllerName = handlerMethod.beanType.simpleName  // e.g. "UserController"
            val entityName = controllerName.removeSuffix("Controller")  // e.g. "User"
            val methodName = handlerMethod.method.name  // e.g. "getById"
            operation.operationId =
                if (entityName.isNotEmpty() && entityName != controllerName &&
                    !methodName.contains(entityName, ignoreCase = true)
                ) {
                    "$methodName$entityName"  // e.g. "getByIdUser", "createCase"
                } else {
                    methodName  // already unique or already contains the entity name
                }
            operation
        }

    /**
     * Keeps `@Schema(nullable = true)` on properties that reference an enum published with `enumAsRef` (e.g.
     * `User.promptCacheTtl`).
     *
     * The spec is OpenAPI 3.1, which has no `nullable` keyword: springdoc turns it into a `"null"` type for
     * inline schemas, but drops it next to a `$ref`. Such a property becomes `oneOf: [$ref, {type: "null"}]`, the
     * 3.1 form of a nullable reference, so the generated clients accept null. Limited to enum references so the
     * existing object references keep their current shape.
     */
    @Bean
    fun nullableEnumRefOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val schemas = openApi.components?.schemas ?: return@OpenApiCustomizer
            val enumRefs = schemas.filterValues { !it.enum.isNullOrEmpty() }.keys.map { "#/components/schemas/$it" }.toSet()
            schemas.values.forEach { schema ->
                schema.properties?.replaceAll { _, property ->
                    if (property.nullable == true && property.`$ref` in enumRefs) property.asNullableRef() else property
                }
            }
        }

    private fun Schema<*>.asNullableRef(): Schema<*> =
        Schema<Any>()
            .description(description)
            .oneOf(listOf(Schema<Any>().`$ref`(`$ref`), Schema<Any>().types(setOf("null"))))
}
