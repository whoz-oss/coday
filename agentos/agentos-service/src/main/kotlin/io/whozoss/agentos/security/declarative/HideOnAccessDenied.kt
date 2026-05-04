package io.whozoss.agentos.security.declarative

/**
 * Marks a controller method that should hide existence on permission denial:
 * an `AccessDeniedException` thrown from this method will be translated to
 * HTTP 404 (`ResourceNotFoundException`) by [AccessDeniedExceptionHandler]
 * instead of the default HTTP 403.
 *
 * Use on `@PreAuthorize`-annotated GET endpoints where leaking the existence
 * of a resource is a security concern (IG1 pattern). Do NOT use on write
 * operations — the caller already submitted an id, so 403 is the correct
 * REST semantics.
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
