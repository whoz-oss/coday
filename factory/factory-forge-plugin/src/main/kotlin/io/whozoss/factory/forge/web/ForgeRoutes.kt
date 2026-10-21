package io.whozoss.factory.forge.web

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.FactoryRoute
import io.whozoss.factory.sdk.spi.FactoryRouteHandler
import io.whozoss.factory.sdk.spi.FactoryRouteRequest
import io.whozoss.factory.sdk.spi.FactoryRouteResponse
import io.whozoss.factory.web.AuthenticatedUser
import io.whozoss.factory.web.AuthenticationFilter
import org.springframework.http.ResponseEntity

/**
 * Functional route table of the Forge plugin.
 *
 * Converts the host's Spring-agnostic [FactoryRouteRequest] into calls on the
 * plain [ForgeRunController] / [JiraProxyController] handler groups and
 * serializes the returned values into [FactoryRouteResponse]. The host mounts
 * the resulting [FactoryRoute]s (see `FactoryPluginRouteConfig`).
 */
object ForgeRoutes {

    private val mapper = ObjectMapper()

    fun routes(forge: ForgeRunController, jira: JiraProxyController): List<FactoryRoute> = buildList {
        // ---- Forge runs -----------------------------------------------------
        addAll(
            routes("GET", "/api/forge/runs", "/api/factory/forge/runs") { req ->
                forge.list(req.query("namespaceId"), req.user())
            },
        )
        addAll(
            routes("POST", "/api/factory/forge/runs/create") { req ->
                forge.create(req.bodyMap(), req.user())
            },
        )

        // ---- G1 / G2 --------------------------------------------------------
        addAll(
            routes("GET", "/api/forge/runs/{id}/gates/G1") { req ->
                forge.g1(req.pathVar("id"), req.query("namespaceId"), req.user())
            },
        )
        addAll(
            routes("GET", "/api/forge/runs/{id}/gates/G2", "/api/factory/forge/runs/{id}/gates/G2") { req ->
                forge.g2(req.pathVar("id"), req.query("namespaceId"), req.user())
            },
        )
        addAll(
            routes("POST", "/api/forge/runs/{id}/gates/G2", "/api/factory/forge/runs/{id}/gates/G2") { req ->
                forge.evaluateG2(req.pathVar("id"), req.query("namespaceId"), req.bodyMap(), req.user())
            },
        )
        addAll(
            routes(
                "POST",
                "/api/forge/runs/{id}/gates/G1/decision",
                "/api/factory/forge/runs/{id}/gates/G1/decision",
            ) { req ->
                forge.g1Decision(req.pathVar("id"), req.query("namespaceId"), req.bodyMap(), req.user())
            },
        )

        // ---- Story slices ---------------------------------------------------
        addAll(
            routes("GET", "/api/forge/runs/{epicRunId}/stories/{storyRunId}/executions") { req ->
                forge.executions(req.pathVar("epicRunId"), req.pathVar("storyRunId"), req.query("namespaceId"), req.user())
            },
        )
        addAll(
            routes("GET", "/api/forge/runs/{epicRunId}/stories/{storyRunId}/oracles") { req ->
                forge.oracles(req.pathVar("epicRunId"), req.pathVar("storyRunId"), req.query("namespaceId"), req.user())
            },
        )
        addAll(
            routes("GET", "/api/forge/runs/{epicRunId}/stories/{storyRunId}/edits") { req ->
                forge.edits(req.pathVar("epicRunId"), req.pathVar("storyRunId"), req.query("namespaceId"), req.user())
            },
        )

        // ---- Story phases ---------------------------------------------------
        addAll(
            routes("POST", "/api/forge/runs/{epicRunId}/stories/{storyRunId}/executions") { req ->
                forge.executeAnalysis(
                    req.pathVar("epicRunId"),
                    req.pathVar("storyRunId"),
                    req.query("namespaceId"),
                    req.bodyMap(),
                    req.user(),
                )
            },
        )
        addAll(
            routes("POST", "/api/forge/runs/{epicRunId}/stories/{storyRunId}/edits") { req ->
                forge.executeEdit(
                    req.pathVar("epicRunId"),
                    req.pathVar("storyRunId"),
                    req.query("namespaceId"),
                    req.bodyMap(),
                    req.user(),
                )
            },
        )
        addAll(
            routes("POST", "/api/forge/runs/{epicRunId}/stories/{storyRunId}/oracles") { req ->
                forge.executeOracles(
                    req.pathVar("epicRunId"),
                    req.pathVar("storyRunId"),
                    req.query("namespaceId"),
                    req.bodyMap(),
                    req.user(),
                )
            },
        )

        // ---- Jira relay -----------------------------------------------------
        addAll(
            routes("GET", "/api/jira/{ticketId}", "/api/factory/jira/{ticketId}") { req ->
                jira.ticket(req.pathVar("ticketId"), req.user())
            },
        )
    }

    private fun routes(
        method: String,
        vararg paths: String,
        handler: (FactoryRouteRequest) -> Any?,
    ): List<FactoryRoute> =
        paths.map { path -> FactoryRoute(method, path, FactoryRouteHandler { req -> respond(handler(req)) }) }

    private fun respond(value: Any?): FactoryRouteResponse = when (value) {
        null, Unit -> FactoryRouteResponse(status = 204, body = null)
        is ResponseEntity<*> -> FactoryRouteResponse(
            status = value.statusCode.value(),
            body = value.body?.let { mapper.writeValueAsString(it) },
        )

        else -> FactoryRouteResponse(body = mapper.writeValueAsString(value))
    }

    private fun FactoryRouteRequest.query(name: String): String? = queryParams[name]?.firstOrNull()

    private fun FactoryRouteRequest.pathVar(name: String): String = pathVariables[name] ?: ""

    /** The user the host's authentication filter attached to the request (always present on guarded api routes). */
    private fun FactoryRouteRequest.user(): AuthenticatedUser =
        attributes[AuthenticationFilter.ATTRIBUTE] as? AuthenticatedUser
            ?: throw io.whozoss.factory.error.UnauthenticatedException()

    private fun FactoryRouteRequest.bodyMap(): Map<String, Any?>? =
        body?.takeIf { it.isNotBlank() }
            ?.let { mapper.readValue(it, object : TypeReference<LinkedHashMap<String, Any?>>() {}) }
}
