# agentos-kotlin-plugin

`KOTLIN` integration of AgentOS (PF4J `ToolPlugin`). It exposes a Kotlin language server (JetBrains `intellij-server`,
over LSP) as tools working on the Kotlin code of a configured project.

## Configuration

| Key                | Required | Description                                                                                   |
|--------------------|----------|-----------------------------------------------------------------------------------------------|
| `projectDirectory` | yes, once merged | Absolute path of the project. No file outside it can be reached. Accepts `{{CASE_EXCHANGE}}`. Set it on the namespace integration. |
| `serverBinary`     | no       | Absolute path of the language server executable. Not inside `projectDirectory`. Falls back on `AGENTOS_KOTLIN_LSP_BINARY`, then `~/kotlin-lsp`. |

The platform and the namespace can each carry a `KOTLIN` integration of the same name: they are merged key by key at run
time. The usual split is `serverBinary` on the platform integration (the binary is a property of the machine) and
`projectDirectory` on the namespace one (the project is a property of the namespace). The schema therefore requires
nothing; the merged configuration does.

An invalid merged config (missing, relative or nonexistent `projectDirectory`) registers no tool and logs an error.
`KOTLIN` is in `agentos.integrations.user-scope-denied-types`: it cannot be created in a user scope.

## Server binary

The binary weighs over 1 GB and is not shipped. It is looked for, first match wins, at:

1. the `serverBinary` parameter of the integration;
2. the environment variable `AGENTOS_KOTLIN_LSP_BINARY` of the service;
3. `~/kotlin-lsp/server/bin/intellij-server`.

The first two are explicit: when set but not executable, the call fails and names where it comes from, with no
fallback. The service executes this file with its own rights, like the commands of a `BASH` integration: only an
administrator can set the parameter (`KOTLIN` is refused in user scopes), and a path inside `projectDirectory` is refused
when the tools are built, since an agent can write there. Keep the binary out of anything an agent can write, the case
directory included.
One server per (binary, project), shared and found again rather than started twice. The first tool call looks in
`~/.coday/agentos/kotlin-lsp/<hash of binary and project>/port` (the file the server publishes with `--port-file`: port,
process id, build) for a server whose process is alive and whose loopback port answers, and connects to it over TCP; when
there is none it starts `intellij-server --multi-client --socket=127.0.0.1:0 --idle-timeout=1h`, with a minimal
environment (no secret of the service), and connects once the port is published. A server holds a lock on its system path
and refuses a second one on it, so a second AgentOS on the same project joins the first one's server. The server is never
stopped by the service: it stops by itself after an hour with no client, and the service hangs up from a server it has
not used for an hour so that it can. Stopping AgentOS only hangs up, the index stays warm.

The server's lock, config, port file and log are in that directory (`log/intellij-server.log`); what the process printed on
start is in `launcher.out` there, and the end of it is in the error an agent receives when a server does not start. The
index databases are not there: they go under the caches directory of the user.
The server listens on the loopback interface, without authentication: any process of the machine can talk to it.

## Tools

All named `kotlin_lsp_*`, prefixed with `<configName>__` when the integration has a name.

18 tools. Each one requires `timeoutSeconds` (1 to 270), which is not repeated in the tables.

**Navigation** by symbol name. Parameters: `symbol` (required, e.g. `AgentLoader`, or `AgentLoader.load` to tell apart
homonyms), optional `file` and optional 1-based `line` (requires `file`) to target the symbol as used there. Answers carry
project-relative paths, 1-based lines, the enclosing symbol and the code itself.

| Tool | What it does | Extra parameters |
|------|--------------|------------------|
| `kotlin_lsp_hover` | Type and documentation of a symbol. | |
| `kotlin_lsp_definition` | Where a symbol is declared, with the declaration code. Give `file` and `line` to follow a usage. | |
| `kotlin_lsp_type_definition` | Where the type of a symbol is declared (e.g. the class of a variable). | |
| `kotlin_lsp_implementation` | Implementations of an interface, abstract class or abstract member. | |
| `kotlin_lsp_references` | Every usage of a symbol in the project, with the code of each usage. Exact: no homonyms, nothing in strings or comments. | `includeDeclaration` (default false) |
| `kotlin_lsp_call_hierarchy_incoming` | Functions that call a function or method. Error if the symbol is not callable. | |
| `kotlin_lsp_call_hierarchy_outgoing` | Functions called by a function or method. Error if the symbol is not callable. | |
| `kotlin_lsp_type_hierarchy` | Supertypes and known subtypes of a class or interface. Error if the symbol is neither. | |

**Search**

| Tool | What it does | Parameters |
|------|--------------|------------|
| `kotlin_lsp_workspace_symbols` | Finds classes, functions, interfaces and objects of the project by name, best match first (exact, prefix, contains). | `query` (required, case-insensitive), `includeLibraries` (default false) |
| `kotlin_lsp_document_symbols` | Lists the symbols declared in one file, with kind and 1-based line. | `file` (required) |

**Refactoring**, which writes on disk. Every edit the server sends is checked against the project and sensitive files before
it is applied.

| Tool | What it does | Parameters |
|------|--------------|------------|
| `kotlin_lsp_rename` | Renames a symbol everywhere it is used, like an IDE rename, and returns the modified files. Strings, comments and config files are not touched. `dryRun` lists the edits instead of applying them. | `symbol` and `newName` (required), `file`, `line`, `dryRun` (default false) |
| `kotlin_lsp_rename_file` | Moves or renames a Kotlin file and fixes what depends on it (imports, the class named after the file). Fails, and writes nothing, if the target exists or is outside the project. Not for a directory or a package. | `file` and `newPath` (required), project-relative |

**Editor**, working on a file or a position. Lines and characters are 0-based, as in the LSP protocol.

| Tool | What it does | Parameters |
|------|--------------|------------|
| `kotlin_lsp_completion` | Completion suggestions at a position: up to 20 items with label, kind and detail. | `file`, `line`, `character` (required) |
| `kotlin_lsp_signature_help` | Signature(s) of the function being called at a position. | `file`, `line`, `character` (required) |
| `kotlin_lsp_diagnostics` | Errors, warnings and hints of a file, with severity and 1-based `line:column`. A file with errors is answered as `KOTLIN_FAILURE`. | `file` (required) |
| `kotlin_lsp_code_actions` | Titles of the quick fixes and refactorings available on a range. Lists them, applies nothing. | `file`, `startLine`, `startCharacter`, `endLine`, `endCharacter` (required) |
| `kotlin_lsp_formatting` | Formatting edits of a whole file, listed and not applied. The server formats like IntelliJ, which is not necessarily the ktlint style of a project. | `file` (required), `tabSize` (default 4), `insertSpaces` (default true) |
| `kotlin_lsp_inlay_hints` | Inferred types, parameter names and return types of a range. Returned no hint in the checks made so far: see [Known limits](#known-limits). | `file`, `startLine`, `startCharacter`, `endLine`, `endCharacter` (required) |

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

Checked against `intellij-server` ILS-271.2085.0 (Alpha), through the shared server:

- `type_hierarchy`, `rename` (dry run) and `workspace_symbols` answered in the checks made on this build; the failures that
  were reported for the previous implementation (in-process stdio server) were not reproduced. Which change removed them
  is not established.
- `inlay_hints` failed with "Internal error" until the client answered the `workspace/configuration` request the server
  makes while computing hints (`LspLanguageClient`: a null per requested item, i.e. the server defaults). It now answers
  without error but returned no hint on the files tried, whose code has plenty of inferred types. Whether the server has
  them off by default or does not produce them in this build is not established: do not rely on it.
- An unqualified common name can time out in `workspace_symbols`. Pass `file` and `line` for common names.
- The first call on a project can come back empty while the server indexes it: retry with a larger `timeoutSeconds`.
- The server of a project is shared by the conversations of the service, and joined by a second service on the same project.
  How it handles the same file opened by two clients is not established.
- One server per project: with `{{CASE_EXCHANGE}}` as `projectDirectory`, every case gets its own server and index, and
  each one stays up for an hour after its last use. Prefer a fixed `projectDirectory` per namespace.
- The release of a connection unused for an hour is checked when a call reaches the pool. A service that receives no Kotlin
  call at all keeps its connections, hence its servers, until it stops; after that they stop on their own idle timeout.
- A server can use up to 2 GB of heap (`ram.xmx` of the build checked), and a launcher output file (`launcher.out`) grows
  with every start, with no rotation.

## Packaging

lsp4j and its JSON-RPC layer are bundled into the plugin JAR. Jackson, the SDK, the Kotlin stdlib and coroutines come
from the service and are excluded.
