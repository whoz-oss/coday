# Implementation Plan — Part 1: Audience Parity & Translation

This document covers two work items that must be complete before the ScheduledPrompt executor
can be removed in favour of the AgentLoop path. Both are **parity traps** (decision record §
"Parity traps", items 1 and 4–5).

---

## Work Item 1 — Widen `ListAgentUsersTool` to cover namespace-deployed agents

### Problem

`Neo4jUserGroupRepository.findUsersByAgentId` only traverses the UserGroup branch:

```kotlin
// Neo4jUserGroupRepository.kt — findUsersByAgentId (current)
MATCH (a:AgentConfig {id: $agentConfigId})-[:DEPLOYED_TO]->(g:UserGroup)
WHERE NOT COALESCE(a.removed, false)
  AND NOT COALESCE(g.removed, false)
  AND g.namespaceId = $namespaceId
MATCH (u:User)-[r:MEMBER|ADMIN]->(g)
WHERE NOT COALESCE(u.removed, false)
WITH u, collect(DISTINCT type(r)) AS rels
RETURN u.id AS userId, u.externalId AS externalId, u.email AS email,
       u.firstname AS firstname, u.lastname AS lastname,
       CASE WHEN 'ADMIN' IN rels THEN 'ADMIN' ELSE 'MEMBER' END AS role
ORDER BY u.externalId ASC
```

`ScheduledPromptUserRunNodeNeo4jRepository.materialize` already uses both branches via
a `CALL { UNION }` subquery:

```cypher
-- Branch 1 (UserGroup deployment)
MATCH (a)-[:DEPLOYED_TO]->(g:UserGroup)-[:BELONGS_TO]->(ns)
  WHERE NOT COALESCE(g.removed, false)
MATCH (u:User)-[:MEMBER|ADMIN]->(g)
  WHERE NOT COALESCE(u.removed, false)
RETURN u.id AS userId
UNION
-- Branch 2 (Namespace deployment)
MATCH (a)-[:DEPLOYED_TO]->(ns)
MATCH (u:User)-[:MEMBER|ADMIN]->(ns)
  WHERE NOT COALESCE(u.removed, false)
RETURN u.id AS userId
```

Every agent deployed directly to a namespace (not via a UserGroup) returns zero users
from the tool, silently targeting nobody in the converged loop.

### Files to modify

| File | Change |
|---|---|
| `agentos/agentos-service/src/main/kotlin/io/whozoss/agentos/userGroup/Neo4jUserGroupRepository.kt` | Replace the Cypher in `findUsersByAgentId` |
| `agentos/agentos-service/src/test/kotlin/io/whozoss/agentos/userGroup/ListAgentUsersToolSpec.kt` | Add parity fixture tests |

No other file changes are needed: `ListAgentUsersTool` delegates to the `usersByAgentLoader`
lambda which is wired in `UserGroupToolPluginConfiguration` to call
`userGroupRepository.findUsersByAgentId`. The lambda signature and the tool's public
interface are unchanged.

### New Cypher for `findUsersByAgentId`

Replace the entire body of the `neo4jClient.query(...)` call in `findUsersByAgentId`:

```cypher
MATCH (a:AgentConfig {id: $agentConfigId})
  WHERE NOT COALESCE(a.removed, false)
MATCH (ns:Namespace {id: $namespaceId})
  WHERE NOT COALESCE(ns.removed, false)
CALL {
    WITH a, ns
    MATCH (a)-[:DEPLOYED_TO]->(g:UserGroup)-[:BELONGS_TO]->(ns)
      WHERE NOT COALESCE(g.removed, false)
    MATCH (u:User)-[r:MEMBER|ADMIN]->(g)
      WHERE NOT COALESCE(u.removed, false)
    RETURN u, collect(DISTINCT type(r)) AS rels
    UNION
    WITH a, ns
    MATCH (a)-[:DEPLOYED_TO]->(ns)
    MATCH (u:User)-[r:MEMBER|ADMIN]->(ns)
      WHERE NOT COALESCE(u.removed, false)
    RETURN u, collect(DISTINCT type(r)) AS rels
}
WITH DISTINCT u, rels
RETURN u.id AS userId, u.externalId AS externalId, u.email AS email,
       u.firstname AS firstname, u.lastname AS lastname,
       CASE WHEN 'ADMIN' IN rels THEN 'ADMIN' ELSE 'MEMBER' END AS role
ORDER BY u.externalId ASC
```

The `CALL { UNION }` mirrors the proven pattern from `materialize`. `DISTINCT u` in the
outer `WITH` deduplicates a user reachable via both branches before the ADMIN/MEMBER
resolution, which is the same semantic as `materialize`'s top-level `WITH DISTINCT userId`.

The `neo4jClient` binding calls must be extended to also bind `namespaceId`:

```kotlin
// Neo4jUserGroupRepository.kt — findUsersByAgentId call site
.bind(agentConfigId.toString()).to("agentConfigId")
.bind(namespaceId.toString()).to("namespaceId")
```

This binding already exists in the current implementation; no signature change is needed.

### Output schema — unchanged

The tool returns `List<UserGroupMember>` (userId, externalId, role, email, firstname,
lastname). The schema does not change. Users reached via namespace deployment carry the
same fields as group-deployed users.

### `role` for namespace-deployed users

A user holds a `[:MEMBER]` or `[:ADMIN]` edge on the `Namespace` node, so `role` remains
meaningful: `ADMIN` edges on a namespace denote namespace administrators, `MEMBER` edges
denote plain members. The ADMIN-wins resolution (`CASE WHEN 'ADMIN' IN rels`) is correct
for both branches.

### Authorization implication

None. The tool already requires the caller to hold Namespace READ (enforced by the
`usersByAgentLoader` lambda in `UserGroupToolPluginConfiguration`). Widening the Cypher
does not grant access to any additional namespace — the query is scoped to the `namespaceId`
passed in the tool context, same as before.

### Parity test

**Spec file to extend**: `ListAgentUsersToolSpec.kt`

The existing spec uses a MockK lambda for `usersByAgentLoader`; it does not exercise
the Cypher. The parity contract requires a test that compares the tool's result set
against the `materialize` Cypher over a shared fixture. Because both queries run against
Neo4j, the test must live in an integration spec that boots an embedded Neo4j context.

The nearest existing integration spec is
`UserGroupControllerIntegrationSpec.kt` (annotated `@SpringBootTest`,
`@ActiveProfiles("test", "embedded-neo4j")`, `@Import(EmbeddedNeo4jTestConfiguration::class)`).
Add a **new** `ListAgentUsersToolIntegrationSpec.kt` in the same package, following the
same `@SpringBootTest` + embedded-Neo4j pattern.

**Fixture** (shared across all assertions in the spec):

| Entity | Description |
|---|---|
| `ns` | One Namespace |
| `agentGroup` | AgentConfig deployed to a UserGroup (`agentGroup -[:DEPLOYED_TO]-> ug`) |
| `agentNs` | AgentConfig deployed directly to the namespace (`agentNs -[:DEPLOYED_TO]-> ns`) |
| `agentBoth` | AgentConfig deployed to both the UserGroup AND the namespace |
| `ug` | UserGroup `[:BELONGS_TO]-> ns` |
| `alice` | `User -[:MEMBER]-> ug` (reachable via group only) |
| `bob` | `User -[:MEMBER]-> ns` (reachable via namespace only) |
| `carol` | `User -[:MEMBER]-> ug` AND `User -[:MEMBER]-> ns` (reachable via both) |
| `removed` | `User` with `removed = true` member of `ug` — must never appear |
| `disabledAgent` | AgentConfig with `enabled = false` — the tool returns NOT_FOUND/empty |

**Assertions** (the parity contract):

1. `listAgentUsers(agentGroup)` → `{alice, carol}` — same set as `materialize(agentGroup)`.
2. `listAgentUsers(agentNs)` → `{bob, carol}` — same set as `materialize(agentNs)`. This
   is the **currently broken case**: the old Cypher returns empty.
3. `listAgentUsers(agentBoth)` → `{alice, bob, carol}` — union deduplicated, `carol`
   appears once. Same set as `materialize(agentBoth)`.
4. `removed` user never appears in any result.
5. `listAgentUsers(disabledAgent)` → empty list or NOT_FOUND (agent guard).

The assertion is **set equality of user ids** between the tool's `UserGroupMember.userId`
list and the userId column returned by the `materialize` query, executed over the same
fixture in the same Neo4j instance. This test **must outlive `materialize()`** — it is the
parity contract between the audience tool and whatever audience-resolution path replaces
the executor.

---

## Work Item 2 — Text-level translation + `scheduledPromptId` passthrough in case-start

### Problem

The current `ScheduledPromptExecutor.translatePromptForUser` method (private, ~50 lines)
translates prompt content and title per user using `PromptService.translate`, then passes
both into `createAndInjectCase`. This method is deleted with convergence.

Two parity traps follow:

- **Trap 4 (Translation)**: the converged `FreeCaseStarterForUserTool` path has no
  translation at all, so every namespace-deployed agent delivers untranslated prompts.
- **Trap 5 (`scheduledPromptId`)**: `Case.scheduledPromptId` is written today only in
  `createAndInjectCase` (the method convergence deletes); the converged path never sets it.

### Decision

Add translation as an **opt-in** on `CaseStarterRequest`, defaulting off, so existing LLM
callers of `FreeCaseStarterForUser` are unaffected. Add an optional `scheduledPromptId`
passthrough on the same request.

Translation at the case-start level must be **text-level** — it cannot call
`PromptService.translate(id, ...)` because the converged path has no `Prompt` entity. The
Caffeine layer inside `PromptTranslationServiceImpl` is already text-shaped, keyed on
`(text, sourceLanguage, targetLanguage, modelId, kind)`. Exposing a text-level translation
capability on `PromptTranslationService` (or a new `TextTranslationService`) unlocks the
cache without requiring a persisted `Prompt`.

### Current signatures

```kotlin
// CaseStarterRequest.kt — current
data class CaseStarterRequest(
    val callerUserId: UUID,
    val callerNamespaceId: UUID,
    val callerCaseId: UUID,
    val agentName: String,
    val prompt: String,
    val targetUserId: UUID? = null,
    val title: String? = null,
    val sessionContext: Map<String, Any?>? = null,
)
```

```kotlin
// PromptTranslationService.kt — current (two separate methods)
fun translateContent(
    content: List<String>,
    sourceLanguage: String,
    targetLanguage: String,
    namespaceId: UUID,
): List<String>

fun translateTitle(
    title: String,
    sourceLanguage: String,
    targetLanguage: String,
    namespaceId: UUID,
): String
```

```kotlin
// CaseStarterService.start — current call sequence
fun start(request: CaseStarterRequest): StartedCase {
    // 1. isInStartedCase check
    // 2. resolveTargetUser
    // 3. findDeployedAgent
    // 4. resolveSessionContext   ← reads targetUser.preferredLanguage here
    // 5. createCase
    // 6. startConversation
}
```

```kotlin
// FreeCaseStarterForUserTool.Input — current
data class Input(
    val agentName: String,
    val prompt: String,
    val userId: String,
    val title: String? = null,
    val sessionContext: Map<String, Any?>? = null,
)
```

### New signatures

**`CaseStarterRequest`** — add two optional fields:

```kotlin
data class CaseStarterRequest(
    val callerUserId: UUID,
    val callerNamespaceId: UUID,
    val callerCaseId: UUID,
    val agentName: String,
    val prompt: String,
    val targetUserId: UUID? = null,
    val title: String? = null,
    val sessionContext: Map<String, Any?>? = null,
    // New: opt-in translation. Null = no translation (default, existing callers unaffected).
    val translateFrom: String? = null,   // BCP-47 source language, e.g. "en"
    // New: passthrough for Case.scheduledPromptId. Null = not a scheduled delivery.
    val scheduledPromptId: UUID? = null,
)
```

`translateFrom` is the source language of the prompt text. When non-null, translation is
attempted for both `prompt` (content) and `title` into the target user's `preferredLanguage`.
When null (the default) no translation is performed — existing LLM callers of
`FreeCaseStarterForUser` are completely unaffected.

**`PromptTranslationService`** — add one new method (text-level, not entity-bound):

```kotlin
// PromptTranslationService.kt — new method
/**
 * Translates [text] from [sourceLanguage] to [targetLanguage] using the
 * namespace's default AI model. Returns [text] unchanged on any failure
 * (LLM error, no model configured) — never throws.
 *
 * [kind] drives the translation context prompt (TITLE vs CONTENT).
 * Results are cached on (text, sourceLanguage, targetLanguage, modelId, kind).
 */
fun translateText(
    text: String,
    sourceLanguage: String,
    targetLanguage: String,
    namespaceId: UUID,
    kind: TextKind,
): String
```

The implementation delegates directly to the private `translateText` method already in
`PromptTranslationServiceImpl` (which has exactly this signature). The only change is
promoting it to the interface.

### Where translation happens in `CaseStarterService.startConversation`

Translation is applied **inside `startConversation`**, after `resolveSessionContext` and
before `caseService.addMessage`. The target user's `preferredLanguage` is already available
from `targetUser` (resolved earlier by `resolveTargetUser`). `startConversation` receives
`targetUser` today; no additional parameter threading is needed.

Concretely, the new step in `startConversation`:

```kotlin
// After grantPermission, before addMessage:
val (effectivePrompt, effectiveTitle) = maybeTranslate(
    request = request,
    targetLanguage = targetUser.preferredLanguage?.takeIf { it.isNotBlank() },
    namespaceId = case.namespaceId,
)
caseService.addMessage(
    caseId = case.id,
    actor = Actor(id = targetUser.id.toString(), displayName = targetUser.displayName(), role = ActorRole.USER),
    content = listOf(MessageContent.Text("@$agentName $effectivePrompt")),
    sessionContext = sessionContext,
)
```

Where `maybeTranslate` is a private helper:

```kotlin
private fun maybeTranslate(
    request: CaseStarterRequest,
    targetLanguage: String?,
    namespaceId: UUID,
): Pair<String, String?> {
    val sourceLanguage = request.translateFrom
    if (sourceLanguage == null ||
        targetLanguage == null ||
        targetLanguage.equals(sourceLanguage, ignoreCase = true)
    ) {
        return request.prompt to request.title
    }
    val translatedPrompt = runCatching {
        promptTranslationService.translateText(
            text = request.prompt,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            namespaceId = namespaceId,
            kind = TextKind.CONTENT,
        )
    }.onFailure { e ->
        logger.warn(e) { "[CaseStarter] translation failed for prompt — using original" }
    }.getOrElse { request.prompt }

    val translatedTitle = request.title?.let {
        runCatching {
            promptTranslationService.translateText(
                text = it,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                namespaceId = namespaceId,
                kind = TextKind.TITLE,
            )
        }.onFailure { e ->
            logger.warn(e) { "[CaseStarter] translation failed for title — using original" }
        }.getOrElse { it }
    }
    return translatedPrompt to translatedTitle
}
```

Translation failure **always degrades to source text** and never fails the start — same
behaviour as `ScheduledPromptExecutor.translatePromptForUser`.

### `scheduledPromptId` passthrough

`createCase` reads `request.scheduledPromptId` and passes it to `Case(...)`:

```kotlin
// CaseStarterService.createCase — current
private fun createCase(request: CaseStarterRequest): Case =
    caseService.create(
        Case(
            namespaceId = request.callerNamespaceId,
            title = (request.title?.takeIf { it.isNotBlank() } ?: request.prompt).take(MAX_TITLE_LENGTH),
            originCaseId = request.callerCaseId,
        ),
    )

// New
private fun createCase(request: CaseStarterRequest): Case =
    caseService.create(
        Case(
            namespaceId = request.callerNamespaceId,
            title = (request.title?.takeIf { it.isNotBlank() } ?: request.prompt).take(MAX_TITLE_LENGTH),
            originCaseId = request.callerCaseId,
            scheduledPromptId = request.scheduledPromptId,   // ← new
        ),
    )
```

Note: after translation `effectiveTitle` replaces `request.title` in the title derivation
expression. `createCase` must therefore receive the effective title, not the raw
`request.title`. The simplest approach: apply translation before `createCase` is called
(i.e. resolve effective prompt and title first, then pass them in), or introduce a
`resolvedTitle` field. The exact refactor shape is an implementation detail, but the
constraint is clear: the case title must be the translated title when translation is active.

### How `preferredLanguage` is obtained

`targetUser` is already resolved by `resolveTargetUser` before `startConversation` is
called. `targetUser.preferredLanguage` is the authoritative source — same as in
`ScheduledPromptExecutor.resolveRunContext`. No additional resolution is required.

### Failure behaviour

Translation failure (LLM error, no model configured, `BadRequestException`) is caught
by `runCatching` inside `maybeTranslate` and logged at WARN. The original text is returned
unchanged. The start proceeds normally. This matches the decision record's requirement
("must degrade to source text, never fail the start").

### `FreeCaseStarterForUserTool.Input` and LLM exposure

`FreeCaseStarterForUserTool.Input` **does not change**. `translateFrom` and
`scheduledPromptId` are **not exposed to LLMs**. They are internal orchestration fields
set by the scheduler's AgentLoop step, not by an LLM. The loop step (a future batch tool)
will construct the `CaseStarterRequest` directly in Kotlin, populating these fields from
the `ScheduledPrompt` entity. Exposing them in the JSON schema would invite LLMs to guess
at source languages or inject arbitrary `scheduledPromptId` values.

The `INPUT_SCHEMA` constant in `FreeCaseStarterForUserTool` and the tool's `description`
string remain unchanged.

### New intra-module Spring dependency: `casePlugin → prompt`

`CaseStarterService` would gain a new dependency on `PromptTranslationService`. Both are
in `agentos-service`; this is an intra-module dependency, not a cross-module one. Spring
wiring is straightforward: constructor injection.

`CaseStarterService` already has a `@Lazy private val caseService` to break a cycle
(`CaseService → AgentService → tool registry → CaseToolPlugin → CaseStarterService`).
`PromptTranslationService` has no dependency on `CaseService` or `AgentService`, so no
cycle is introduced. The new dependency is acceptable.

### Tests to add

**Spec file**: `CaseStarterServiceUnitSpec.kt`

Add a new section "Translation" after the existing "Session context" section. All tests
use the existing `Fixture` class with a MockK `PromptTranslationService` injected into
`CaseStarterService`. The `Fixture` class gains one optional constructor parameter:
`promptTranslationService: PromptTranslationService = mockk(relaxed = true)`.

Required test cases:

1. **`translateFrom` null (default)** — `promptTranslationService` is never called;
   `addMessage` receives the original `prompt` string.
2. **`translateFrom` non-null, user has `preferredLanguage`** — `translateText(CONTENT)`
   and `translateText(TITLE)` are each called once; `addMessage` receives the translated
   text; case title is the translated title.
3. **`translateFrom` matches `preferredLanguage`** (same language, e.g. both `"en"`) —
   `translateText` is never called; original text used.
4. **Translation failure (CONTENT)** — `translateText` throws; `addMessage` receives the
   original prompt; the start completes (UserRun is not failed).
5. **Translation failure (TITLE)** — same: original title used, start completes.
6. **User has no `preferredLanguage`** — `translateFrom` is set but `preferredLanguage` is
   null; `translateText` is never called.
7. **`scheduledPromptId` non-null** — `caseService.create` is called with a `Case` whose
   `scheduledPromptId` equals the value in the request.
8. **`scheduledPromptId` null (default)** — `caseService.create` is called with
   `scheduledPromptId = null`.

Existing tests in `CaseStarterServiceUnitSpec` must remain green without modification:
the new fields default to null, so no existing `request(...)` call changes behaviour.
