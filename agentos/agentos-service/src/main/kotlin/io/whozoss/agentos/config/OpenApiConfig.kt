package io.whozoss.agentos.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
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
}
