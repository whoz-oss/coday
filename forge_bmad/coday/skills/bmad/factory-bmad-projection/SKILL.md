---
name: factory-bmad-projection
description: "Map a Forge/BMAD Jira Story lifecycle to the generic Factory projection contract."
---

# Forge/BMAD Factory Projection Adapter

Apply this adapter together with the generic `core/factory-workflow-projection/SKILL.md` protocol.

## Domain workflow declaration

- **workflowType:** `bmad-story`.
- **definitionVersion:** `1.0.0` (immutable definition: `factory/workflows/bmad-story/1.0.0.json`).
- **Compatible intent/reference:** a request to start or resume the approval-gated Forge/BMAD lifecycle for a Jira Story. Jira and Story interpretation is specific to this adapter, not to Factory's generic entry.
- **Stable identity rule:** resolve the Jira Story through the deterministic Forge Run reconnaissance; use its Story key unchanged as `workflowId` and its Jira title as `title`. A missing or unavailable Story cannot establish identity and must halt.
- **Sole publisher role:** ProductEngineer. BmadOrchestrator, BmadBuilder, Designer, and Reviewer report domain facts and outcomes to ProductEngineer and never publish the same workflow.
- **Lifecycle source:** the Forge Run Protocol, deterministic `forge-run-recon`, confirmed workstream, BMAD Gates 1–4, and the instance-state mappings below. Recon, explicit human confirmation, workstream confirmation, and BMAD gates remain mandatory. The versioned definition, not this prose, is authoritative for stable graph structure.

The workflow selector for this adapter is `--workflow=bmad-story`.

## Stable structure and instance state

Publish schema v2 using the exact steps, responsibilities, and dependencies from `bmad-story@1.0.0`. Responsibility describes the executor: both `implementation` and `review-rework` are agent work owned by BmadBuilder, even though their deliverable is code. Reserve `code` for deterministic Factory-owned execution; no such extra oracle/build/test step is introduced in this Phase 2 definition. ProductEngineer remains the sole publisher; delegates receive no Factory tools. The definition contains no statuses or instance descriptions. Those remain projection state derived from current Jira/BMAD facts. Phase 2 does not yet let the tool derive a projection automatically from the definition, so copy the structure exactly and vary only statuses and genuinely instance-specific descriptions. Do not add optional dependency branches in prose: a new graph requires a new immutable definition version.

## BMAD mapping

- A gate not yet eligible is `pending`; once all dependencies and required inputs are satisfied, it is `ready`; active work is `running`; an approved/passed gate is `completed`.
- A BMAD checkpoint or HALT awaiting explicit human validation maps the active gate and workflow to `waiting_human`.
- A recoverable technical, artifact, dependency, or review impediment maps the affected gate and workflow to `blocked`.
- A terminal unsuccessful BMAD outcome maps the affected gate and workflow to `failed`. An explicitly abandoned run maps to `cancelled`.
- Review findings that require another implementation cycle keep `gate-3` `running` or `blocked` according to whether work can proceed; they do not complete it.
- The workflow is `completed` only after `gate-1` through `gate-4` are all `completed`. It is `failed` or `cancelled` only on the corresponding terminal domain outcome.

ProductEngineer reconstructs the complete projection from Jira and BMAD facts reported by delegated agents, publishes material and terminal transitions, then reports them. This adapter does not authorize deletion or cleanup of BMAD, Jira, Git, worktree, PR, case, or conversation artifacts.
