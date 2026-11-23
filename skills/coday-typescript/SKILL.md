---
name: coday-typescript
description: Coday TypeScript runtime and Express server work in libs/ and apps/server (handler design, Nx library boundaries, TypeScript conventions). Read before changing non-Angular TypeScript code.
---

# Coday TypeScript

Use this skill for non-Angular TypeScript: the Coday runtime and its libraries under `libs/` (handlers, agents,
integrations, services), the Express server in `apps/server` and the `scripts/` utilities. Angular code belongs to
the `angular-frontend` skill and Kotlin code to `agentos-backend`.

## Read before coding

Open with `FILES` the documents that match the task, and follow them over this summary:

- `docs/to-rework/ARCHITECTURE.md`: runtime, server layers, route/service patterns, SSE, thread lifecycle
- `docs/to-rework/HANDLER_DESIGN.md`: nested handlers, add-edit delegation, `parseArgs`
- `docs/to-rework/DEV_WORKFLOW.md`: branches, commits, pull requests, verification commands
- `apps/client/docs/nx-tasks.md`: Nx targets and affected runs

The other documents of `docs/to-rework/` (agent definitions, integrations, MCP, AI configuration, project
configuration) cover their own topic: open them when the task touches it.

## Conventions

- The repository is an Nx monorepo managed with pnpm. Respect library boundaries: depend on the public entry point
  of a library, never reach into another library's internals. Dependency versions go in the pnpm workspace
  catalog, not in individual `package.json` files.
- Command handlers are nested (`NestedHandler`), `add` handlers create a default entity then delegate to the `edit`
  handler, and arguments are parsed with `parseArgs` with explicit named flags (`--project/-p` is the standard).
- Server routes are organised by domain entity in `*.routes.ts`, business logic and persistence live in services,
  and the username comes from the `getUsernameFn(req)` helper. Validate path parameters and request bodies.
- `apps/server` is a prototype that AgentOS will replace: keep changes there minimal and do not add architecture.
- Follow the surrounding code's style (naming, error handling, event emission) rather than introducing new
  patterns.

## Tests

Add tests only in the established patterns of the project (Jest, next to the code). If a test pattern is not clear
for the area, say so.

## Verification

Run what applies and report exactly what you ran and what you could not run:

```bash
pnpm nx lint <project>
pnpm nx test <project>
pnpm nx run-many -t build
pnpm nx affected -t lint test
```
