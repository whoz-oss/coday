package io.whozoss.factory.sdk.spi

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Spring-agnostic HTTP request handed to a plugin route handler.
 *
 * The host is responsible for mapping its native request object onto this
 * structure so the SPI never leaks a web framework type.
 */
data class FactoryRouteRequest(
    val method: String,
    val path: String,
    val pathVariables: Map<String, String> = emptyMap(),
    val queryParams: Map<String, List<String>> = emptyMap(),
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String? = null,
)

/**
 * Spring-agnostic HTTP response returned by a plugin route handler.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FactoryRouteResponse(
    val status: Int = 200,
    val contentType: String = "application/json",
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String? = null,
)

/**
 * Functional handler of a [FactoryRoute].
 */
fun interface FactoryRouteHandler {
    fun handle(request: FactoryRouteRequest): FactoryRouteResponse
}

/**
 * Spring-agnostic route descriptor contributed by a plugin.
 *
 * @param method the HTTP method (e.g. `GET`, `POST`), case-insensitive.
 * @param path the route path template, e.g. `/api/factory/custom`.
 * @param handler the functional handler invoked for a matching request.
 */
data class FactoryRoute(
    val method: String,
    val path: String,
    val handler: FactoryRouteHandler,
)
