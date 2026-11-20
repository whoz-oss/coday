# agentos-ktlint-plugin

`KTLINT` integration of AgentOS (PF4J `ToolPlugin`). It runs the embedded ktlint engine (standard ruleset,
the target project's `.editorconfig` applies) on the Kotlin files of a configured project.

## Configuration

| Key                | Required | Description                                                                                   |
|--------------------|----------|-----------------------------------------------------------------------------------------------|
| `projectDirectory` | yes      | Absolute path of the project. No file outside it can be reached. Accepts `{{CASE_EXCHANGE}}`. |

An invalid config (missing, relative or nonexistent `projectDirectory`) registers no tool and logs an error.

## Tools

- `ktlint_check`: reports the violations of the given `.kt` / `.kts` files, one line each
  (`file:line:column message (rule-id)`, flagged `[auto-correctable]` when `ktlint_format` can fix it). Never modifies files.
- `ktlint_format`: autocorrects the given files in place, then lists the modified files and the violations left to fix by hand.

Both tools take files only (directories are refused) and a mandatory `timeoutSeconds`. On timeout,
`ktlint_format` lists the files it already rewrote. Cancelling the call (stopping the case) stops the run between two files.
The file in progress is finished first, since the engine does not check interruption while parsing.

## Packaging

ktlint (rule engine, standard ruleset, `kotlin-compiler-embeddable`) is bundled into the plugin JAR: the service
does not provide it. Jackson, the SDK, the Kotlin stdlib and coroutines come from the service and are excluded.
