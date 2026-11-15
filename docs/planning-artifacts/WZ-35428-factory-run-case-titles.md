# WZ-35428 — Factory run case titles: preliminary scope

Jira: https://whoz.atlassian.net/browse/WZ-35428

This is a preliminary scoping plan, not an approved technical specification. Exact integration contracts remain to be verified against the base branch.

## Intended behavior

- Apply to all new Factory runs. Jira reference, title, and long instructions are three independent optional inputs.
- For the parent case, display the Jira key first when present, followed by the explicit title. Only when a title is absent, asynchronously generate a one-line summary of the instructions. Title generation must not delay launch.
- If generation fails, use the normal AgentOS naming fallback while retaining the Jira key; if no fallback is available, retain the key alone. If all inputs are absent, use normal AgentOS naming.
- A manual rename wins over any in-flight generation result.
- Name step subcases with the step label and authoritative attempt number, for example `Technical assessment #1`. Do not rename historical cases.

## Technical investigation and verification plan

- Trace all run entrypoints and parent/step case creation on `workstream/forge/master` at `60107b9efcde5184cf6ee8ea2aa9276ce2e94485`; verify integration contracts before selecting implementation changes.
- Preserve the distinction between an explicit title and a default workflow title; reuse AgentOS naming where appropriate. Source the attempt number from its authoritative owner.
- Design race-safe manual-rename precedence, preserving idempotency and retries across creation, asynchronous completion, and recovery.
- Verify input combinations, late generation and manual updates, generation failures and fallbacks, nonblocking launch, recovery and retries, step attempts, and existing behavior.
