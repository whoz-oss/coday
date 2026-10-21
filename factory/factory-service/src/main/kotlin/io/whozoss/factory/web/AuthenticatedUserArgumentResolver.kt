package io.whozoss.factory.web

import io.whozoss.factory.error.UnauthenticatedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Injects the [AuthenticatedUser] attached by [AuthenticationFilter] into a
 * controller parameter, so a handler can simply declare `user: AuthenticatedUser`.
 */
class AuthenticatedUserArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AuthenticatedUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any =
        webRequest.getNativeRequest(HttpServletRequest::class.java)?.let(AuthenticationFilter::from)
            ?: throw UnauthenticatedException()
}
