package io.whozoss.factory.workflow.domain

/** Bounded, explicit routing: named choices, not expressions or caller-selected step IDs. */
data class WorkflowRoute(val name: String, val target: String) {
    fun toJson(): Map<String, Any?> = linkedMapOf("name" to name, "target" to target)
}

data class WorkflowDecision(val mode: String, val routes: List<WorkflowRoute>) {
    fun toJson(): Map<String, Any?> = linkedMapOf("mode" to mode, "routes" to routes.map { it.toJson() })

    fun accepts(selection: List<String>?): Boolean = selection != null && selection.isNotEmpty() &&
        selection.size == selection.distinct().size && selection.all { name -> routes.any { it.name == name } } &&
        (mode == "multiple" || (mode == "single" && selection.size == 1))

    companion object {
        /** Strict parser shared by definition ingestion and trusted projection reconstruction. */
        fun parse(value: Any?): WorkflowDecision? {
            val raw = value as? Map<*, *> ?: return null
            if (raw.keys.toSet() != setOf("mode", "routes")) return null
            val mode = raw["mode"] as? String ?: return null
            if (mode !in setOf("single", "multiple")) return null
            val entries = raw["routes"] as? List<*> ?: return null
            if (entries.size !in 2..16) return null
            val safe = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
            val routes = entries.map { entry ->
                val route = entry as? Map<*, *> ?: return null
                if (route.keys.toSet() != setOf("name", "target")) return null
                val name = route["name"] as? String ?: return null
                val target = route["target"] as? String ?: return null
                if (!safe.matches(name) || !safe.matches(target)) return null
                WorkflowRoute(name, target)
            }
            if (routes.map { it.name }.distinct().size != routes.size || routes.map { it.target }.distinct().size != routes.size) return null
            return WorkflowDecision(mode, routes)
        }
    }
}

fun WorkflowStepDefinition.routingJson(): Map<String, Any?> = buildMap {
    decision?.let { put("decision", it.toJson()) }
    join?.let { put("join", it) }
}

object WorkflowRouting {
    /** Initial scope deliberately excludes nested, overlapping and multi-step branch paths. */
    fun topologyError(steps: List<WorkflowStepDefinition>): String? {
        val decisions = steps.filter { it.decision != null }
        val joins = steps.filter { it.join != null }
        if (decisions.isEmpty()) return if (joins.isEmpty()) null else "join_without_decision"
        if (decisions.size != 1 || joins.size != 1) return "exactly_one_decision_and_join_required"
        val decision = decisions.single()
        val join = joins.single()
        val routes = decision.decision ?: return "invalid_decision"
        if (WorkflowDecision.parse(routes.toJson()) == null) return "invalid_decision"
        if (decision.responsibility.kind != ResponsibilityKind.AGENT) return "decision_must_be_agent"
        if (join.join != decision.id || join.id == decision.id) return "join_must_reference_decision"
        val targets = routes.routes.map { it.target }.toSet()
        if (decision.id in targets || join.id in targets) return "invalid_route_target"
        for (target in targets) {
            val branch = steps.find { it.id == target } ?: return "missing_route_target"
            if (branch.dependsOn != listOf(decision.id)) return "branch_must_depend_only_on_decision"
            if (steps.filter { target in it.dependsOn }.map { it.id } != listOf(join.id)) return "only_join_may_consume_branch"
        }
        if (!join.dependsOn.containsAll(targets)) return "join_must_depend_on_every_route_target"
        if (steps.any { decision.id in it.dependsOn && it.id !in targets && it.id != join.id }) return "decision_fanout_must_use_routes"
        return null
    }

    /** Only the explicit convergence may consume inactive predecessors. Never relax ordinary AND. */
    fun dependenciesSatisfied(step: WorkflowStepDefinition, steps: List<WorkflowStepDefinition>, statuses: Map<String, String>): Boolean {
        val decision = steps.find { it.id == step.join }
        val conditional = decision?.decision?.routes?.map { it.target }?.toSet().orEmpty()
        if (step.join != null && (decision == null || statuses[decision.id] != WorkflowStatuses.COMPLETED)) return false
        if (step.join != null && conditional.none { statuses[it] == WorkflowStatuses.COMPLETED }) return false
        return step.dependsOn.all { statuses[it] == WorkflowStatuses.COMPLETED || (it in conditional && statuses[it] == WorkflowStatuses.SKIPPED) }
    }
}
