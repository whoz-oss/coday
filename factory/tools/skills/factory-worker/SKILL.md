---
name: factory-worker
description: Complete a Factory agent attempt and submit a factual business result through FACTORY_WORKER, with safe validation-error recovery and idempotency handling.
---

# Factory worker

Use this skill when a Factory brief assigns an agent attempt and requires a structured business result. This document provides business guidance; the tool's input schema is the authority for payload structure and constraints.

## Required context

Read the current brief, acceptance criteria and available tools before working. Use the Factory `attemptId` and `Idempotency-Key` supplied in the brief. Never derive them from an AgentOS case ID or invent replacements. If they or the submission tool are missing, report the blocker instead of claiming successful submission.

This role requires only the `FACTORY_WORKER` integration. `FACTORY_WORKER__submitStepResult` submits the attempt result. Workflow inspection and steering through `FACTORY_WORKSTREAM` belong to a separate role and skill. Do not start workflows or request retries as part of this worker role; report blockers to the steering role or the user.

## Work and evidence

Complete the assigned scope and verify the acceptance criteria. Distinguish observed facts, unverified assumptions and remaining work. Never present a planned test as executed, a local edit as deployed, or an unavailable check as passing.

Choose the business verdict from evidence:

- `PASS`: the required acceptance criteria are satisfied and supported by available evidence.
- `FAIL`: the work or evaluation establishes that required criteria are not satisfied. Explain the failures and remaining blockers.
- `NEEDS_RESEARCH`: missing information requires research before a reliable business verdict can be made. State the precise unanswered question and what evidence is needed. Do not use this as a generic substitute for a failed check or a tool transport error. Submission does not itself prove that a subsequent research workflow has run.

Follow the step-specific brief for the business information required. Keep the summary concise and factual. Populate `claims.modifiedFiles` with the files actually modified for the attempt, or an empty array if none were modified. Do not claim ownership of pre-existing changes.

Use artifacts for substantive deliverables the brief requires and findings for actionable observations, using the tool's supported fields, encodings and severities. Do not invent fields for evidence: place it in the supported summary, artifact or finding content. Do not include secrets or credentials.

## Submit the result

1. Confirm the work is ready for a verdict and inspect the submission tool's current schema, including nested required fields.
2. Call `FACTORY_WORKER__submitStepResult` using structured tool arguments, not JSON in a normal assistant message. Supply the brief's attempt and submission identifiers through the fields exposed by the HTTP tool.
3. If an expected amendment sequence is required by the assignment, use its authoritative value. Do not guess it or silently replace it to bypass stale-state checks.
4. Inspect the tool response. An assistant's final message, an AgentOS idle state, or a transport timeout is not evidence of Factory acceptance.
5. Once Factory explicitly accepts the result, stop the attempt's work. A short human-facing summary may report that acceptance, but must not replace submission.

## Recover safely

- `RESULT_SCHEMA_INVALID`: the submission was rejected. Read `error.details.violations`, correct the indicated fields against the tool schema and resubmit using the assigned identifiers. Do not change the business verdict merely to pass structural validation. If details are unavailable, compare the payload with the full schema; avoid repeating an unchanged invalid request.
- Transport failure or ambiguous response: do not assume acceptance or rejection. If retrying, replay the exact same payload with the same idempotency key. Do not modify the payload while the prior submission's outcome is unknown.
- `IDEMPOTENCY_KEY_COLLISION` or `RESULT_SEMANTIC_COLLISION`: stop and reconcile the previously submitted result through available authorized inspection or escalation. Never generate a fresh key to bypass a collision.
- `STALE_AMENDMENT_SEQUENCE`: reconcile the current assignment and amendment state before proceeding. Do not blindly increment the sequence.
- Missing authorization, expired capability, unavailable tools, or repeated unexplained rejection: report the precise blocker. Do not claim the attempt was accepted or start an uncontrolled retry loop.

## Installation

This is a portable skill document, not an automatically registered integration. Load its contents into the worker agent's instructions (or its deployment's supported skill mechanism) and explicitly attach `FACTORY_WORKER` in AgentOS. This role does not require `FACTORY_WORKSTREAM`. See `factory/tools/FACTORY_WORKSTREAM_HTTP_API.md` from the repository root for integration setup. Updating this file alone does not update existing agents or their deployed OpenAPI configuration.
