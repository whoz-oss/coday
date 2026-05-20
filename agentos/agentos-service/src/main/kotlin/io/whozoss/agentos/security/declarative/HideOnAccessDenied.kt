package io.whozoss.agentos.security.declarative

/**
 * Marks a controller method that should hide existence on permission denial:
 * an `AccessDeniedException` thrown from this method will be translated to
 * HTTP 404 (`ResourceNotFoundException`) by [AccessDeniedExceptionHandler]
 * instead of the default HTTP 403.
 *
 * Use on `@PreAuthorize`-annotated endpoints — `GET`, `PUT` and `DELETE` — where
 * leaking the existence of a resource is a security concern (IG1 pattern, FR21,
 * NFR-SEC-2). The unified Epic 6 controllers ([io.whozoss.agentos.aiProvider.AiProviderController],
 * [io.whozoss.agentos.integrationConfig.IntegrationConfigController]) apply it
 * uniformly across read **and** write verbs to keep cross-user probes
 * indistinguishable from unknown ids — a non-owner cannot tell apart "row exists,
 * I cannot touch it" from "row does not exist". The 404-on-write convention is a
 * deliberate trade-off : strict REST semantics would prefer 403, but an existence
 * oracle on write is a worse leak than the violated convention.
 *
 * Skip the annotation only on writes that *must* surface a 403 (bulk operations
 * over already-known ids, admin tooling, or audit-style endpoints where the
 * caller is expected to have proven existence elsewhere).
 *
 * Example:
 * ```
 * @GetMapping("/{id}")
 * @PreAuthorize("hasPermission(#id, 'Case', 'READ')")
 * @HideOnAccessDenied
 * fun getById(@PathVariable id: UUID): CaseResource = ...
 * ```
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class HideOnAccessDenied
