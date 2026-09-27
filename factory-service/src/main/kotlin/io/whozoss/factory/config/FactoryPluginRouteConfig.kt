package io.whozoss.factory.config

import io.whozoss.factory.sdk.spi.FactoryRoute
import io.whozoss.factory.sdk.spi.FactoryRouteContributor
import io.whozoss.factory.sdk.spi.FactoryRouteRequest
import mu.KLogging
import org.pf4j.PluginManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.RequestPredicates
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.util.Optional

/**
 * Dynamically mounts every [FactoryRoute] contributed by the PF4J
 * [FactoryRouteContributor] extensions as a Spring MVC `RouterFunction`.
 *
 * The router is always a bean: with zero installed plugins it is an empty
 * router, so the application context still starts cleanly and the pre-existing
 * `@RestController` surfaces (`/api/forge/...`, …) keep working unchanged.
 */
@Configuration
class FactoryPluginRouteConfig {

    @Bean
    fun pluginRouterFunction(pluginManager: PluginManager): RouterFunction<ServerResponse> {
        val routes = pluginManager
            .getExtensions(FactoryRouteContributor::class.java)
            .flatMap { it.getRoutes() }

        if (routes.isEmpty()) {
            logger.info { "No plugin routes contributed; mounting an empty plugin router" }
            // A never-matching router: `RouterFunctions.route().build()` would throw
            // (Spring forbids building a builder with zero registered routes).
            return RouterFunction<ServerResponse> { Optional.empty() }
        }

        logger.info { "Mounting ${routes.size} plugin route(s) contributed by PF4J extensions" }
        val builder = RouterFunctions.route()
        routes.forEach { route -> builder.add(toRouterFunction(route)) }
        return builder.build()
    }

    private fun toRouterFunction(route: FactoryRoute): RouterFunction<ServerResponse> {
        val method = HttpMethod.valueOf(route.method.uppercase())
        val predicate = RequestPredicates.method(method).and(RequestPredicates.path(route.path))
        return RouterFunctions.route(predicate, toHandler(route))
    }

    private fun toHandler(route: FactoryRoute): HandlerFunction<ServerResponse> =
        HandlerFunction { request ->
            val pluginResponse = route.handler.handle(request.toFactoryRequest())
            val responseBuilder = ServerResponse
                .status(HttpStatusCode.valueOf(pluginResponse.status))
                .contentType(MediaType.parseMediaType(pluginResponse.contentType))
                .headers { headers ->
                    pluginResponse.headers.forEach { (name, values) -> values.forEach { headers.add(name, it) } }
                }
            pluginResponse.body?.let { responseBuilder.body(it) } ?: responseBuilder.build()
        }

    private fun ServerRequest.toFactoryRequest(): FactoryRouteRequest =
        FactoryRouteRequest(
            method = method().name(),
            path = path(),
            pathVariables = pathVariables(),
            queryParams = params(),
            headers = headers().asHttpHeaders().mapValues { it.value },
            body = runCatching { body(String::class.java) }.getOrNull(),
        )

    companion object : KLogging()
}
