package io.whozoss.factory.forge.web

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.forge.port.ForgePluginHttpException
import io.whozoss.factory.forge.port.ForgeUnauthenticatedException
import io.whozoss.factory.sdk.spi.FactoryCallerIdentity
import io.whozoss.factory.sdk.spi.FactoryRoute
import io.whozoss.factory.sdk.spi.FactoryRouteHandler
import io.whozoss.factory.sdk.spi.FactoryRouteRequest
import io.whozoss.factory.sdk.spi.FactoryRouteResponse

/**
 * Functional route table of the Forge plugin.
 *
 * Converts the host's Spring-agnostic [FactoryRouteRequest] into calls on the
 * plain [JiraProxyController] handler group and serializes the returned values
 * (or [ForgePluginHttpException] error envelopes) into [FactoryRouteResponse].
 */
object ForgeRoutes {

    private val mapper = ObjectMapper()

    fun routes(jira: JiraProxyController): List<FactoryRoute> = buildList {
        addAll(
            routes("GET", "/api/jira/{ticketId}", "/api/factory/jira/{ticketId}") { req ->
                jira.ticket(req.pathVar("ticketId"), req.requireCaller())
            },
        )
    }

    private fun routes(
        method: String,
        vararg paths: String,
        handler: (FactoryRouteRequest) -> Any?,
    ): List<FactoryRoute> =
        paths.map { path ->
            FactoryRoute(
                method = method,
                path = path,
                handler = FactoryRouteHandler { req ->
                    try {
                        respond(handler(req))
                    } catch (e: ForgePluginHttpException) {
                        val envelope = ForgeErrorEnvelope(
                            ForgeErrorDetail(
                                code = e.errorCode,
                                message = e.message,
                                details = e.details,
                            ),
                        )
                        FactoryRouteResponse(
                            status = e.statusCode,
                            body = mapper.writeValueAsString(envelope),
                        )
                    }
                },
            )
        }

    private fun respond(value: Any?): FactoryRouteResponse = when (value) {
        null, Unit -> FactoryRouteResponse(status = 204, body = null)
        is FactoryRouteResponse -> value
        else -> FactoryRouteResponse(body = mapper.writeValueAsString(value))
    }

    private fun FactoryRouteRequest.pathVar(name: String): String = pathVariables[name] ?: ""

    private fun FactoryRouteRequest.requireCaller(): FactoryCallerIdentity =
        caller ?: throw ForgeUnauthenticatedException()
}
