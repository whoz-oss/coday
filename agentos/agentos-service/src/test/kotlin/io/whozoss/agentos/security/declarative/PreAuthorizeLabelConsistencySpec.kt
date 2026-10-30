package io.whozoss.agentos.security.declarative

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController

/**
 * Validates that every `@PreAuthorize` annotation on every `@RestController` in the service
 * references only entity labels and actions that the permission evaluator can actually resolve.
 *
 * ## Why this test exists
 *
 * [AgentOsPermissionEvaluator.hasPermission] converts the string arguments of a SpEL
 * `hasPermission(id, 'Label', 'Action')` call into typed [EntityType] and [Action] values.
 * When either lookup fails the evaluator **fails closed**: it returns `false` and emits only a
 * WARN log. It does NOT throw. Consequences:
 *
 * - A typo in an entity label (e.g. `'AGENT_CONFIG'` instead of `'AgentConfig'`) makes every
 *   caller of that endpoint receive 403 / 404, **including super-admins**, because the
 *   super-admin bypass lives inside `PermissionService.hasPermission()`, which is never reached
 *   when [EntityType.fromLabel] returns null.
 * - A non-existent action (e.g. `'SUPER_ADMIN'`) makes `Action.valueOf()` throw inside a
 *   `runCatching`, which yields null, which again short-circuits to `false`.
 * - Neither failure produces a startup error, a crash, or even an ERROR log entry. Without this
 *   spec a single-character mistake is an invisible, total, silent outage of a whole endpoint
 *   family — exactly the defect this spec was introduced to prevent recurring.
 *
 * **Labels must be the PascalCase Neo4j node label** carried by [EntityType.label]
 * (e.g. `'AgentConfig'`, `'Namespace'`), NOT the Kotlin enum constant name (`'AGENT_CONFIG'`).
 * Actions must be one of the [Action] entries: `'READ'`, `'WRITE'`, `'DELETE'`.
 *
 * ## Scope
 *
 * Only `hasPermission(...)` calls are validated. Other SpEL expressions (`isAuthenticated()`,
 * `hasRole(...)`, `permitAll()`) do not pass through [AgentOsPermissionEvaluator] and are not
 * subject to the same silent-failure mode, so they are intentionally ignored.
 */
class PreAuthorizeLabelConsistencySpec :
    StringSpec({

        "all @PreAuthorize hasPermission labels and actions must be resolvable" {
            val scanner = ClassPathScanningCandidateComponentProvider(false)
            scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))

            val collected = mutableListOf<PermissionCheck>()

            for (beanDef in scanner.findCandidateComponents(BASE_PACKAGE)) {
                val className = beanDef.beanClassName ?: continue
                val clazz = Class.forName(className)

                // Type-level @PreAuthorize (rare, but legal).
                clazz.getAnnotation(PreAuthorize::class.java)?.let { annotation ->
                    collectChecks(className, "<class>", annotation.value, collected)
                }

                // Method-level @PreAuthorize — the usual case.
                for (method in clazz.declaredMethods) {
                    val annotation = method.getAnnotation(PreAuthorize::class.java) ?: continue
                    collectChecks(className, method.name, annotation.value, collected)
                }
            }

            // Sanity guard: a misconfigured scanner (wrong base package, missing classpath) would
            // otherwise let this spec pass vacuously with zero assertions actually performed.
            collected.shouldNotBeEmpty()

            val violations =
                collected.mapNotNull { check ->
                    val problems = mutableListOf<String>()
                    if (EntityType.fromLabel(check.label) == null) {
                        problems +=
                            "entity label '${check.label}' is not resolvable — it must match an " +
                                "EntityType.label (PascalCase Neo4j node label such as 'AgentConfig'), not the " +
                                "enum constant name such as 'AGENT_CONFIG'. Known labels: " +
                                EntityType.entries.joinToString { "'${it.label}'" }
                    }
                    if (Action.entries.none { it.name == check.action }) {
                        problems +=
                            "action '${check.action}' is not a valid Action — must be one of " +
                                Action.entries.joinToString { "'${it.name}'" }
                    }
                    if (problems.isEmpty()) {
                        null
                    } else {
                        "${check.controllerClass}.${check.location} " +
                            "SpEL=[${check.spel}]: ${problems.joinToString("; ")}"
                    }
                }

            if (violations.isNotEmpty()) {
                throw AssertionError(
                    "Found ${violations.size} unresolvable @PreAuthorize hasPermission argument(s). " +
                        "Each of these silently denies ALL callers, including super-admins:\n" +
                        violations.joinToString("\n") { "  - $it" },
                )
            }
        }
    })

private const val BASE_PACKAGE = "io.whozoss.agentos"

/**
 * Matches every `hasPermission(arg, 'Label', 'Action')` occurrence in a SpEL string.
 * Applied with [Regex.findAll] so a ternary expression carrying two `hasPermission` calls
 * has both of them validated.
 */
private val HAS_PERMISSION_REGEX = Regex("""hasPermission\(\s*[^,]+,\s*'([^']+)'\s*,\s*'([^']+)'\s*\)""")

private data class PermissionCheck(
    val controllerClass: String,
    val location: String,
    val spel: String,
    val label: String,
    val action: String,
)

private fun collectChecks(
    controllerClass: String,
    location: String,
    spel: String,
    into: MutableList<PermissionCheck>,
) {
    // Expressions with no hasPermission call are out of scope by design.
    if (!spel.contains("hasPermission")) return
    for (match in HAS_PERMISSION_REGEX.findAll(spel)) {
        into +=
            PermissionCheck(
                controllerClass = controllerClass,
                location = location,
                spel = spel,
                label = match.groupValues[1],
                action = match.groupValues[2],
            )
    }
}
