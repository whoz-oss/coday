package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/**
 * Refuses to start when the opt-in `FACTORY_WORKSTREAM` service identity is
 * enabled with an invalid configuration. A disabled identity is never validated
 * and never authenticates anyone.
 */
@Component
class WorkstreamServiceAccessValidator(
    private val properties: FactoryProperties,
) : InitializingBean {

    override fun afterPropertiesSet() {
        if (!properties.security.workstreamService.enabled) return
        val problems = WorkstreamServiceAccessRules.problems(properties.security)
        check(problems.isEmpty()) {
            "Invalid factory.security.workstream-service configuration: ${problems.joinToString("; ")}"
        }
    }
}
