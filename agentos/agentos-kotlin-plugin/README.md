# agentos-kotlin-plugin

`KOTLIN` integration of AgentOS (PF4J `ToolPlugin`). It exposes a Kotlin language server (JetBrains `intellij-server`,
over LSP) as tools working on the Kotlin code of a configured project.

## Configuration

| Key                | Required | Description                                                                                   |
|--------------------|----------|-----------------------------------------------------------------------------------------------|
| `projectDirectory` | yes      | Absolute path of the project. No file outside it can be reached. Accepts `{{CASE_EXCHANGE}}`. |

An invalid config (missing, relative or nonexistent `projectDirectory`) registers no tool and logs an error.
`KOTLIN` is in `agentos.integrations.user-scope-denied-types`: it cannot be created in a user scope.

## Server binary

The binary weighs over 1 GB and is not shipped. It is looked for, first match wins, at:

1. the environment variable `AGENTOS_KOTLIN_LSP_BINARY` of the service (when set but not executable, the call fails
   and names the path, with no fallback);
2. `~/kotlin-lsp/server/bin/intellij-server`.

It is deliberately not an integration parameter and never looked for inside the project: the service executes it.
One server per (binary, project), started by the first tool call, with a minimal environment (no secret of the
service). Its index, caches and log live in `~/.coday/agentos/kotlin-lsp/<hash of the project>`.

## Tools

All named `kotlin_lsp_*`, prefixed with `<configName>__` when the integration has a name.

- Navigation by symbol name, with an optional `file` and 1-based `line`: `hover`, `definition`, `type_definition`,
  `implementation`, `references`, `call_hierarchy_incoming`, `call_hierarchy_outgoing`, `type_hierarchy`.
- Search: `workspace_symbols`, `document_symbols`.
- Refactoring, writing on disk: `rename` (`dryRun` lists the edits), `rename_file`.
- Editor, 0-based positions: `completion`, `signature_help`, `diagnostics`, `code_actions`, `formatting` (edits
  listed, not applied), `inlay_hints`.

Every path stays under `projectDirectory` (symbolic links resolved) and a sensitive file or `.git` is refused, in the
edits the server sends back as well. `timeoutSeconds` (1 to 270) is mandatory and bounds the whole call, server start
included: give the first call a large value, the server indexes the project. Cancelling the case cancels the request.

## Results

| Outcome   | Error type       | Meaning                                                                       |
|-----------|------------------|-------------------------------------------------------------------------------|
| `Success` | none             | the server answered                                                           |
| `Failure` | `KOTLIN_FAILURE` | `diagnostics` found errors: the text is the list, the answer the agent needs  |
| `Error`   | `KOTLIN_ERROR`   | the tool could not run (bad input, path refused, symbol not resolved, server unavailable, timeout) |

## Known limits

From a field test of the previous implementation, not re-run since the port: `type_hierarchy` and `inlay_hints`
failed on every call, `rename` produced no edit (a missing client capability is suspected and was added since), and
an unqualified common name can time out in `workspace_symbols`. Pass `file` and `line` for common names.

## Packaging

lsp4j and its JSON-RPC layer are bundled into the plugin JAR. Jackson, the SDK, the Kotlin stdlib and coroutines come
from the service and are excluded.
