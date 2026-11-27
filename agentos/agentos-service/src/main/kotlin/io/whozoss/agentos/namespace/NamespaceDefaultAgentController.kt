package io.whozoss.agentos.namespace

import io.swagger.v3.oas.annotations.media.Schema
import io.whozoss.agentos.agent.DefaultAgentResolver
import io.whozoss.agentos.user.UserService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Which agent answers the caller's first message without `@mention` in a namespace, so the composer of a
 * new case can say it. Resolved for the caller by [DefaultAgentResolver], the rule the case runtime routes with.
 */
@RestController
class NamespaceDefaultAgentController(
    private val defaultAgentResolver: DefaultAgentResolver,
    private val userService: UserService,
) {
    @GetMapping("/api/namespaces/{namespaceId}/default-agent", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun get(
        @PathVariable namespaceId: UUID,
    ): NamespaceDefaultAgentResource =
        NamespaceDefaultAgentResource(
            agentName = defaultAgentResolver.resolve(namespaceId, userService.getCurrentUser().id).agentName,
        )
}

/** The agent the caller's message without `@mention` reaches in a new case; null when none would answer. */
@Schema(name = "NamespaceDefaultAgent")
data class NamespaceDefaultAgentResource(
    val agentName: String? = null,
)
