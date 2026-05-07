package io.whozoss.agentos.permissions

/**
 * Type-safe registry of entity types known to the permission system.
 *
 * Each entry's [label] matches the Neo4j node label exactly — the same string used in
 * `MATCH (e:Label)` Cypher queries and in `@PreAuthorize("hasPermission(#id, 'Label', ...)")`
 * SpEL expressions.
 *
 * **Why an enum** : story 5-4's [io.whozoss.agentos.entity.EntityController] declared
 * `entityType: String`, which let typos (`"AgenConfig"`) compile cleanly while silently
 * denying all reads at runtime (the corresponding Neo4j label simply has no matches).
 * Replacing the string with an enum makes typos compile-time errors and lets IDE refactor
 * tools rename atomically across all call sites.
 *
 * **SpEL still uses strings** : Spring Security `@PreAuthorize` annotations cannot
 * idiomatically reference enum constants — the `T(...).LABEL` syntax is unreadable.
 * The boundary between string-typed SpEL and the typed Kotlin API lives in
 * [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator], which converts
 * via [fromLabel] and fails closed (returning `false` + WARN log) on unknown labels.
 *
 * **Spring Data Neo4j `@Query` also keeps strings** : the SDN driver cannot bind a Kotlin
 * enum to a `$entityLabel` Cypher parameter. The conversion `entityType.label` happens
 * once at the boundary in [Neo4jPermissionRepository] before delegating to
 * [PermissionNodeNeo4jRepository].
 */
enum class EntityType(val label: String) {
    AGENT_CONFIG("AgentConfig"),
    AI_MODEL("AiModel"),
    AI_PROVIDER("AiProvider"),
    CASE("Case"),
    CASE_EVENT("CaseEvent"),
    INTEGRATION_CONFIG("IntegrationConfig"),
    NAMESPACE("Namespace"),
    USER("User"),
    ;

    override fun toString(): String = label

    companion object {
        private val byLabel: Map<String, EntityType> = entries.associateBy { it.label }

        /**
         * Resolve a string label to its [EntityType] constant, or `null` if no entry matches.
         *
         * Returns `null` rather than throwing — callers (typically the permission evaluator
         * bridging from SpEL) are expected to fail closed and log a WARN when this happens,
         * because the only realistic source of an unknown label is a typo in a SpEL annotation
         * that should be surfaced rather than crash the request.
         */
        fun fromLabel(label: String): EntityType? = byLabel[label]
    }
}
