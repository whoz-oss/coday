package io.whozoss.factory.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Resolves a `TrustContext` controller parameter from the request attribute set
 * by [TrustContextFilter]. This is the `@RequestAttribute`-style accessor: a
 * handler method may declare `fun endpoint(trustContext: TrustContext)` and
 * receive the boundary-resolved context without re-parsing headers.
 */
class TrustContextArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == TrustContext::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any? {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java) ?: return null
        return TrustContextFilter.from(request)
    }
}
