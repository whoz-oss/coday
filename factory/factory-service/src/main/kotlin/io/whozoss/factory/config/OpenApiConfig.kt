package io.whozoss.factory.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun factoryOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Factory Service API")
                .description("REST API for the Factory Service — autonomous Kotlin/Spring Boot backend")
                .version("0.0.1"),
        )

    /**
     * Override operationId to produce globally-unique, readable names.
     *
     * By default springdoc appends numeric suffixes (_1, _2…) when several
     * controllers share a method name. The OpenAPI spec requires globally unique
     * operationIds, so we build them as "{methodName}{EntityName}" in camelCase
     * — e.g. `getByIdUser`, `createCase`. This produces clean method names in a
     * generated client while remaining unambiguous globally. Controllers that do
     * not follow the "XxxController" convention fall back to the bare method
     * name (already unique for non-entity controllers).
     */
    @Bean
    fun methodNameOperationCustomizer(): OperationCustomizer =
        OperationCustomizer { operation, handlerMethod ->
            val controllerName = handlerMethod.beanType.simpleName
            val entityName = controllerName.removeSuffix("Controller")
            val methodName = handlerMethod.method.name
            operation.operationId =
                if (entityName.isNotEmpty() &&
                    entityName != controllerName &&
                    !methodName.contains(entityName, ignoreCase = true)
                ) {
                    "$methodName$entityName"
                } else {
                    methodName
                }
            operation
        }
}
