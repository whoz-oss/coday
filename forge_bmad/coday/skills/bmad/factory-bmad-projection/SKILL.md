---
name: factory-bmad-projection
description: "Map a Forge/BMAD Jira Story lifecycle to the generic Factory projection contract."
---

# Forge/BMAD Factory Projection Adapter

Apply this adapter together with the generic `core/factory-workflow-projection/SKILL.md` protocol.

## Domain workflow declaration

- **workflowType:** `bmad-story`.
- **Compatible intent/reference:** a request to start or resume the approval-gated Forge/BMAD lifecycle for a Jira Story. Jira and Story interpretation is specific to this adapter, not to Factory's generic entry.
- **Stable identity rule:** resolve the Jira Story through the deterministic Forge Run reconnaissance; use its Story key unchanged as `workflowId` and its Jira title as `title`. A missing or unavailable Story cannot establish identity and must halt.
- **Sole publisher role:** ProductEngineer. BmadOrchestrator, BmadBuilder, Designer, and Reviewer report domain facts and outcomes to ProductEngineer and never publish the same workflow.
- **Lifecycle source:** the Forge Run Protocol, deterministic `forge-run-recon`, confirmed workstream, BMAD Gates 1–4, and the stable schema-v2 step graph and mappings below. Recon, explicit human confirmation, workstream confirmation, and BMAD gates remain mandatory.

The workflow selector for this adapter is `--workflow=bmad-story`.

## Stable steps and explicit actors

Publish schema v2 and this meaningful lifecycle graph. ProductEngineer remains the sole publisher; delegates receive no Factory tools.

1. `ticket-analysis` — qualify intent and evidence; actor `agent`, name `ProductEngineer`; no dependencies.
2. `intent-checkpoint` — explicit product-owner confirmation when required; actor `human`, name `Product owner`; depends on `ticket-analysis`.
3. `specification` — produce the implementation-ready specification; actor `agent`, name `BmadOrchestrator`; depends on `intent-checkpoint` (or directly on `ticket-analysis` when the checkpoint is not required).
4. `readiness-checkpoint` — explicit human readiness approval when required; actor `human`, name `Product owner`; depends on `specification`.
5. `implementation` — change source and tests; actor `code`, name `BmadBuilder`; depends on `readiness-checkpoint` (or `specification` when no checkpoint is required).
6. `technical-review` — adversarial technical review; actor `agent`, name `Reviewer`; depends on `implementation`.
7. `review-rework` — code corrections required by review; actor `code`, name `BmadBuilder`; depends on `technical-review`; keep pending when no rework is required and do not fabricate activity.
8. `functional-validation` — validate behavior and acceptance criteria; actor `agent`, name `ProductEngineer`; depends on `technical-review` or `review-rework` when rework occurred.
9. `acceptance-checkpoint` — explicit final human acceptance when required; actor `human`, name `Product owner`; depends on `functional-validation`.

Keep IDs stable. Model lifecycle handoffs, not low-level tool calls. A checkpoint awaiting input is explicitly `waiting_human`; do not hide it in an agent or code step.

## BMAD mapping

- A gate not yet eligible is `pending`; once all dependencies and required inputs are satisfied, it is `ready`; active work is `running`; an approved/passed gate is `completed`.
- A BMAD checkpoint or HALT awaiting explicit human validation maps the active gate and workflow to `waiting_human`.
- A recoverable technical, artifact, dependency, or review impediment maps the affected gate and workflow to `blocked`.
- A terminal unsuccessful BMAD outcome maps the affected gate and workflow to `failed`. An explicitly abandoned run maps to `cancelled`.
- Review findings that require another implementation cycle keep `gate-3` `running` or `blocked` according to whether work can proceed; they do not complete it.
- The workflow is `completed` only after `gate-1` through `gate-4` are all `completed`. It is `failed` or `cancelled` only on the corresponding terminal domain outcome.

ProductEngineer reconstructs the complete projection from Jira and BMAD facts reported by delegated agents, publishes material and terminal transitions, then reports them. This adapter does not authorize deletion or cleanup of BMAD, Jira, Git, worktree, PR, case, or conversation artifacts.
