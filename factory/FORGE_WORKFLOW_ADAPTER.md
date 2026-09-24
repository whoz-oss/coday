# Forge/BMAD → WorkflowProjection v1

BMAD is authoritative. There is one publication pipeline:

```
agent updates forge/state/forge-runs/<TICKET>.yaml
  → POST /api/factory/forge/projections/<TICKET>/sync?namespaceId=<uuid>
  → strict BMAD read
  → deterministic Forge adapter
  → generic WorkflowProjection store
  → workflow-projection-updated SSE
  → generic Angular workflow state
```

The workflow ID is `forge-run-<TICKET>`, its type is `forge-ticket-v1`, and its four stable linear steps are `gate-1` Ticket, `gate-2` Spec, `gate-3` Tech Review, and `gate-4` Func Review. No LLM translates gate state.

The strict reader requires a non-empty mapping, a `ticket_id` matching the route, a supported `run_outcome.status`, valid gate objects and timestamps, and coherent gate progression. Its dependency-free syntax check deliberately accepts only the mapping/scalar subset used by representative copied Forge runs. Lists, flow collections, block scalars, tabs, malformed mappings, dangling quotes, incomplete block values, and other exotic YAML are rejected even when a full YAML implementation might accept them. Invalid input fails closed and leaves the last valid generic revision unchanged.

`forge-gate-run.ts` updates the known `gate_N` fields atomically with a temporary file and rename while preserving unrelated YAML text, comments, and unknown fields. Approved paths write `started_at`, `decided_at`, and `human_decision`; Gate 4 additionally changes only `run_outcome.status` to `completed`, preserving other outcome fields. Generic synchronization runs once after the durable rename.

Agents call `coday/scripts/forge-workflow-sync.ts <TICKET>` after updating the YAML. The script requires `NAMESPACE_ID` or `FACTORY_NAMESPACE_ID`, accepts `FACTORY_SERVER_URL` (default `http://localhost:3141`), calls only the explicit sync route, and treats server/network publication failures as non-blocking.

The Forge cockpit keeps independently persisted JSONL run operations and workstream navigation. Synchronized BMAD run state is displayed only by Generic workflows; the former Forge-specific Gantt HTTP/SSE projection is removed.

## Manual diagnostic sync

```bash
curl -i -X POST \
  'http://127.0.0.1:3141/api/factory/forge/projections/WZ-34411/sync?namespaceId=<UUID>' \
  -H 'content-type: application/json' \
  -d '{"actorId":"manual-smoke"}'

curl -s \
  'http://127.0.0.1:3141/api/factory/workflows?namespaceId=<UUID>&state=active'

curl -N \
  'http://127.0.0.1:3141/api/factory/workflows/stream?namespaceId=<UUID>'
```

The HTTP response wraps synchronization facts in `data`; the integration script reads `data.changed` to report `published` or `unchanged`. Repeating an unchanged sync should return `changed: false` and emit no update event.
