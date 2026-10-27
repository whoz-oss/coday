package io.whozoss.agentos.configAgent

/** The refs of a proposal: all of them, and those of the operations that create a namespace. */
data class PlanRefs(
    val all: Set<String>,
    val namespaces: Set<String>,
)
