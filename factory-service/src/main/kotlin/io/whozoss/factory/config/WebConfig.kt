package io.whozoss.factory.config

import io.whozoss.factory.web.TrustContextArgumentResolver
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * MVC wiring for the Factory HTTP boundary.
 *
 * Registers [TrustContextArgumentResolver] so a `TrustContext` controller
 * parameter is injected from the request attribute set by the trust-context
 * filter.
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(TrustContextArgumentResolver())
    }
}
