# ADR 001 — Neo4j Soft-Delete via Labels

## Status

Proposed

## Context

Soft-delete is currently expressed as a `removed: Boolean` property on every node. This causes
two problems:

1. Every active-only query carries boilerplate: `WHERE removed IS NULL OR removed = false`.
2. Uniqueness constraints cannot target active entities only, so a secondary `ActiveUser` /
   `ActiveUserGroup` label was introduced — creating fragile dual bookkeeping between the
   property and the label.

## Options

### Option A — `User` (active) / `RemovedUser` (deleted)

Active nodes carry the natural label. Deleted nodes have their label replaced by a
`Removed`-prefixed one.

Queries read as pure domain intent, no filter needed:
```cypher
MATCH (u:User)-[:MEMBER]->(g:UserGroup) RETURN u
```

Uniqueness constraints sit on the natural label and automatically cover active entities only.

**SDN trade-off.** `@Node("User")` binds `Neo4jRepository` to the `User` label. Deleted nodes
(`:RemovedUser`) are invisible to SDN-generated queries. Accessing or restoring them requires
explicit raw Cypher via `Neo4jClient`. Given that accessing deleted entities is rare and the
codebase already uses `Neo4jClient` for complex queries, this is acceptable.

### Option B — `User` + `ActiveUser` (active) / `User` (deleted)

Active nodes carry both labels; deleted nodes keep only the primary label. SDN can still read
all nodes via `@Node("User")`.

This solves the dual-bookkeeping problem but not the readability one: routine queries must use
`ActiveUser` instead of `User`, which is no more natural than `WHERE removed = false`.

### Option C — `User` + `AllUser` (active) / `AllUser` (deleted)

A rename of Option B that restores natural label semantics. Active nodes carry both `User` and
`AllUser`; deleted nodes carry only `AllUser`. `User` is the label for routine queries;
`AllUser` is the umbrella label that covers the full history.

```cypher
-- routine query, no filter needed
MATCH (u:User)-[:MEMBER]->(g:UserGroup) RETURN u

-- full history (audit, restoration)
MATCH (u:AllUser {id: $id}) RETURN u
```

SDN's `@Node("User")` covers active nodes natively, same as Option A. Deleted nodes are
accessible via `Neo4jClient` using `AllUser`. The difference from A is purely in how the
non-active state is expressed: `AllUser` reads as a scope ("the full set") rather than a
status ("this node is removed"), which requires knowing the convention to interpret correctly.
The difference from B is that the routine label is the natural one.

## Decision

Pending discussion.
