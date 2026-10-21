package io.whozoss.factory.config

import io.whozoss.factory.web.AuthenticatedUserArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * MVC wiring for the Factory HTTP boundary.
 *
 * Registers [AuthenticatedUserArgumentResolver] so an `AuthenticatedUser`
 * controller parameter is injected from the request attribute set by the
 * authentication filter.
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AuthenticatedUserArgumentResolver())
    }
}
