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

Both tools take files only (directories are refused) and a mandatory `timeoutSeconds` (1 to 270, kept under the
5 minutes of the context cache). On timeout,
`ktlint_format` lists the files it already rewrote. Cancelling the call (stopping the case) stops the run between two files.
The file in progress is finished first, since the engine does not check interruption while parsing.

A report lists at most 200 violations (`MAX_REPORTED_VIOLATIONS`), so that a generated file cannot flood the context of
the agent: the others are replaced by a line `... N more violation(s) not shown (X total)`. The counts of the header
(`Checked N file(s): M violation(s)`, `K remaining`) stay the real totals. The cap applies to `ktlint_check`, to the violations
left by `ktlint_format` and to the violations listed when a run stops on a timeout or a failure.

`ktlint_format` writes each file to a temporary file in the same directory and moves it over the original, so that a full
disk or a killed service never leaves a half-written source (the permissions of the file are kept).

The ktlint runs share a small pool of 2 to 4 platform threads (half of the processors). It is created again if the plugin is
stopped then started, and a run that finds it unavailable answers a `KTLINT_ERROR` instead of failing. One ktlint engine is
built per project and shared by all the tool calls on it.

## Results

The outcome of a call is decided where it is known (`KtlintToolResult`), never guessed from the text:

| Outcome   | Error type       | Meaning                                                                         |
|-----------|------------------|---------------------------------------------------------------------------------|
| `Success` | none             | ktlint ran, nothing is left to fix                                              |
| `Failure` | `KTLINT_FAILURE` | ktlint ran and violations remain: the text is the report, the answer the agent needs |
| `Error`   | `KTLINT_ERROR`   | the tool could not do its job (bad input, path refused, timeout, interruption)  |

## Packaging

ktlint (rule engine, standard ruleset, `kotlin-compiler-embeddable`) is bundled into the plugin JAR: the service
does not provide it. Jackson, the SDK, the Kotlin stdlib and coroutines come from the service and are excluded.
