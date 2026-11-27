package io.whozoss.factory.workflow.domain

/** Definition-owned routing. Workers cannot choose arbitrary destinations. */
data class OutcomeAction(val action: String, val target: String? = null, val maxCycles: Int? = null) {
    fun toJson(): Map<String, Any?> = buildMap {
        put("action", action)
        target?.let { put("target", it) }
        maxCycles?.let { put("maxCycles", it) }
    }
}

data class OutcomeTransitions(val pass: OutcomeAction? = null, val fail: OutcomeAction? = null) {
    fun toJson(): Map<String, Any?> = buildMap {
        pass?.let { put("pass", it.toJson()) }
        fail?.let { put("fail", it.toJson()) }
    }

    companion object {
        fun parse(value: Any?): OutcomeTransitions? {
            val raw = value as? Map<*, *> ?: return null
            if (raw.keys.any { it !in setOf("pass", "fail") }) return null
            fun action(key: String): OutcomeAction? {
                val item = raw[key] as? Map<*, *> ?: return null
                val name = item["action"] as? String ?: return null
                val targeted = name == (if (key == "pass") "activate" else "return")
                if (name !in (if (key == "pass") setOf("continue", "activate") else setOf("block", "return"))) return null
                val allowed = if (!targeted) setOf("action") else if (key == "pass") setOf("action", "target") else setOf("action", "target", "maxCycles")
                if (item.keys.any { it !in allowed }) return null
                val target = item["target"] as? String
                if (targeted && (target == null || !Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$").matches(target))) return null
                val budget = if (item.containsKey("maxCycles")) {
                    val number = item["maxCycles"] as? Number ?: return null
                    val integer = number.toInt()
                    if (number.toDouble() != integer.toDouble() || integer !in 1..10) return null
                    integer
                } else null
                return OutcomeAction(name, target, budget)
            }
            val pass = if (raw.containsKey("pass")) action("pass") ?: return null else null
            val fail = if (raw.containsKey("fail")) action("fail") ?: return null else null
            return OutcomeTransitions(pass, fail)
        }
    }
}

/** Pure replay planning. dependsOn remains an acyclic AND graph. */
object OutcomeReplayPlan {
    /**
     * The only code step (by `responsibility.name`) that may declare `fail.return`: the deterministic GitHub PR
     * checks step, and only for a real red CI verdict. Duplicated from the capability package on purpose, to keep the
     * domain free of it; a test asserts both constants are equal.
     */
    const val CI_RETURN_CAPABLE = "github-pr-checks"

    fun scope(steps: List<WorkflowStepDefinition>, target: String): Set<String> {
        val result = linkedSetOf(target)
        do {
            val previous = result.size
            steps.filter { it.dependsOn.any(result::contains) }.forEach { result.add(it.id) }
        } while (result.size != previous)
        return result
    }

    fun error(steps: List<WorkflowStepDefinition>): String? {
        val regions = mutableListOf<Pair<Set<String>, Int>>()
        for (step in steps) {
            val transitions = step.outcomeTransitions ?: continue
            if (OutcomeTransitions.parse(transitions.toJson()) == null) return "invalid_outcome_transitions"
            transitions.pass?.takeIf { it.action == "activate" }?.let { pass ->
                if (step.decision != null || steps.none { it.id == pass.target && step.id in it.dependsOn }) return "pass_target_must_be_direct_successor_without_decision"
                if (steps.any { it.decision?.routes?.any { route -> route.target == pass.target } == true }) return "pass_target_is_conditional"
            }
            val fail = transitions.fail?.takeIf { it.action == "return" } ?: continue
            // Eligible triggers: an accepted structured agent FAIL, or a real red CI verdict of the CI-check code step.
            // Every other code/human failure (and a CI timeout or refusal) blocks.
            val ciReturnCapable = step.responsibility.kind == ResponsibilityKind.CODE && step.responsibility.name == CI_RETURN_CAPABLE
            if (step.responsibility.kind != ResponsibilityKind.AGENT && !ciReturnCapable) return "return_requires_agent_result"
            val target = fail.target ?: return "return_target_missing"
            if (steps.none { it.id == target } || step.id !in scope(steps, target)) return "return_target_must_be_self_or_ancestor"
            val region = scope(steps, target)
            // A partial decision region would reuse stale activation. Reject, never guess a larger target.
            for (decision in steps.filter { it.decision != null }) {
                val group = setOf(decision.id) + decision.decision!!.routes.map { it.target } + steps.filter { it.join == decision.id }.map { it.id }
                if (group.any(region::contains) && !region.containsAll(group)) return "return_splits_decision_region"
            }
            // Branch setup is not a correction operation. Explicitly reject replay that could recreate it.
            if (steps.any { it.id in region && (it.responsibility.name == "Gitay" || it.responsibility.name == "prepare-work-branch") }) return "return_replays_branch_initialization"
            val budget = fail.maxCycles ?: 2
            if (regions.any { (other, max) -> other.intersect(region).isNotEmpty() && (other != region || max != budget) }) return "overlapping_return_regions"
            regions += region to budget
        }
        return null
    }
}
