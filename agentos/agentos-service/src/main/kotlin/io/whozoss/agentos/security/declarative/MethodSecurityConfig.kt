package io.whozoss.agentos.security.declarative

import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Spring Security wiring for AgentOS declarative permissions.
 *
 * - Activates `@PreAuthorize` / `@PostAuthorize` evaluation via [EnableMethodSecurity]
 * - Plugs [AgentOsPermissionEvaluator] into the SpEL `hasPermission(...)` keyword
 * - Configures a permissive [SecurityFilterChain] that delegates auth decisions to
 *   method-level annotations ( will add the annotations to controllers)
 * - Registers [AgentOsAuthenticationFilter] before [UsernamePasswordAuthenticationFilter]
 *   so the [SecurityContextHolder] is populated before any `@PreAuthorize` runs
 *
 * Default Spring Security filters that we explicitly disable to preserve the existing
 * AgentOS API behavior:
 * - CSRF: disabled — REST API, no browser sessions
 * - form login: disabled — auth is handled upstream (Cloudflare JWT / OS username)
 * - HTTP Basic: disabled — same reason
 */
@Configuration
@EnableMethodSecurity(prePostEnabled = true)
class MethodSecurityConfig {

    @Bean
    fun methodSecurityExpressionHandler(
        evaluator: AgentOsPermissionEvaluator,
    ): MethodSecurityExpressionHandler =
        DefaultMethodSecurityExpressionHandler().apply {
            setPermissionEvaluator(evaluator)
        }

    /**
     * Disables Spring Boot's automatic registration of [AgentOsAuthenticationFilter] as a top-level
     * servlet filter. The filter is wired exclusively into the Spring Security chain via
     * `addFilterBefore(...)` in [securityFilterChain] — registering it twice would cause every
     * authenticated request to resolve the user identity (and hit the database) twice.
     */
    @Bean
    fun agentOsAuthFilterRegistration(
        filter: AgentOsAuthenticationFilter,
    ): FilterRegistrationBean<AgentOsAuthenticationFilter> =
        FilterRegistrationBean(filter).apply { isEnabled = false }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        agentOsFilter: AgentOsAuthenticationFilter,
        accessDeniedHandler: AccessDeniedExceptionHandler,
    ): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .addFilterBefore(agentOsFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { it.accessDeniedHandler(accessDeniedHandler) }
            .build()
}
