# Hermes Agent — Memory & Self-Learning System Analysis

> Study of https://github.com/NousResearch/hermes-agent memory architecture.
> Sources: repo code (`agent/memory_manager.py`, `agent/memory_provider.py`, `agent/prompt_builder.py`, `agent/curator.py`, `tools/memory_tool.py`, `tools/skill_manager_tool.py`, `tools/session_search_tool.py`) + [official docs](https://hermes-agent.nousresearch.com/docs/user-guide/features/overview).

## Architecture Overview

3 memory layers + 1 self-improvement loop:

```
┌─────────────────────────────────────────────────────────┐
│                    System Prompt                        │
│  [MEMORY.md snapshot] [USER.md snapshot] [Skills index] │
└─────────────────────────────────────────────────────────┘
         ↑ injected once at session start

┌─────────────────────────────────────────────────────────┐
│              MemoryManager (orchestrator)                │
│  BuiltinMemoryProvider + max 1 external provider        │
│  → prefetch_all() → sync_all() per turn                 │
└─────────────────────────────────────────────────────────┘

┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐
│  MEMORY.md   │  │   USER.md    │  │  session_search (FTS5)│
│  2200 chars  │  │  1375 chars  │  │  SQLite ~/.hermes/    │
│  ~800 tokens │  │  ~500 tokens │  │  state.db — unlimited │
└──────────────┘  └──────────────┘  └──────────────────────┘

┌─────────────────────────────────────────────────────────┐
│            Self-learning loop                           │
│  skill_manage (create/modify skills)                    │
│  + Curator (background skill maintenance)               │
└─────────────────────────────────────────────────────────┘
```

## 1. Declarative Memory — MEMORY.md / USER.md

### Storage
- `~/.hermes/memories/MEMORY.md` — agent personal notes (env, conventions, learned errors)
- `~/.hermes/memories/USER.md` — user profile (preferences, communication style)
- Hard limits: 2200 chars for MEMORY, 1375 for USER

### "Frozen snapshot" pattern
At each session start, content is injected **once** into the system prompt (frozen snapshot). Never updated mid-session → preserves LLM prefix cache. Modifications via the `memory` tool are persisted to disk immediately but only visible at next session.

### System prompt format
```
══════════════════════════════════════════════
MEMORY (your personal notes) [67% — 1,474/2,200 chars]
══════════════════════════════════════════════
User's project is a Rust web service at ~/code/myapi using Axum + SQLx
§
This machine runs Ubuntu 22.04, has Docker and Podman installed
§
User prefers concise responses, dislikes verbose explanations
```
Entries separated by `§`.

### Tool `memory`
Actions: `add`, `replace`, `remove` via substring matching (`old_text` = unique substring).
When memory is full (>80%), agent must consolidate before adding.

### When the agent writes to memory
Guided by `MEMORY_GUIDANCE` in `agent/prompt_builder.py` — proactive save of:
- User preferences
- Corrections received
- Project conventions
- Environment facts
- Completed work

## 2. MemoryManager — Multi-provider Abstraction

`agent/memory_manager.py` — single integration point in `run_agent.py`.

- **1 BuiltinMemoryProvider** always present
- **Max 1 external provider** (Honcho, Mem0, OpenViking, Hindsight…) — intentional limit to avoid schema bloat

`MemoryProvider` interface (`agent/memory_provider.py`):
```python
def system_prompt_block(self) -> str
def prefetch(self, query, *, session_id) -> str       # recall before each turn
def queue_prefetch(self, query, ...)                  # async prefetch next turn
def sync_turn(self, user_content, assistant_content)  # post-turn persistence
def get_tool_schemas(self) -> List[Dict]
def on_turn_start(self, turn_number, message, **kwargs)
def on_session_end(self, messages)                    # fact extraction at session end
def on_delegation(self, task, result, child_session_id)
def on_memory_write(self, action, target, content, metadata)  # cross-provider sync
```

Per-turn cycle in `run_agent.py`:
```
session start → initialize_all(session_id)
  → build_system_prompt()  [frozen snapshot]

per turn:
  → prefetch_all(user_message)   [context recall injected via <memory-context>]
  → [LLM call + tool loop]
  → sync_all(user_msg, assistant_response)
  → queue_prefetch_all(user_msg) [async prefetch for next turn]
```

`StreamingContextScrubber` strips `<memory-context>` tags from stream so they don't appear in UI.

## 3. Session Search — Long-term Memory

`tools/session_search_tool.py` — cross-session recall via SQLite FTS5:

1. FTS5 on `~/.hermes/state.db` — all CLI and gateway sessions
2. Top N relevant sessions (default 3)
3. Each session truncated to ~100k chars centered on matches
4. LLM summary (fast auxiliary model, e.g. Gemini Flash) per session
5. Returns structured summaries with metadata

| | Persistent memory | Session Search |
|---|---|---|
| Capacity | ~1300 tokens total | Unlimited |
| Speed | Instant (system prompt) | Search + LLM |
| Use case | Key facts always available | "Did we discuss X last week?" |
| Management | Agent-curated | Automatic — everything stored |

## 4. Procedural Memory — Skills System

### Structure
```
~/.hermes/skills/
├── my-skill/
│   ├── SKILL.md          # instructions (required)
│   ├── references/
│   ├── templates/
│   └── scripts/
└── .usage.json           # telemetry (use_count, view_count, patch_count, state)
```

### Progressive disclosure (token-efficient)
```
Level 0: skills_list()          → [{name, description, category}]  (~3k tokens)
Level 1: skill_view(name)       → full content
Level 2: skill_view(name, path) → specific reference file
```
Level 0 index injected in every system prompt.

### Tool `skill_manage` — autonomous writing
Actions: `create`, `patch` (preferred, token-efficient), `edit`, `delete`, `write_file`, `remove_file`.

**When the agent creates a skill**:
- After a complex task (≥5 tool calls) resolved successfully
- When it encountered errors and found the working path
- When the user corrected its approach
- When it discovered a non-trivial workflow

## 5. Curator — Self-improvement Loop

`agent/curator.py` — background maintenance of agent-created skills.

### Trigger
No cron daemon — triggered by inactivity:
- At CLI session start
- On recurring tick in gateway
- Conditions: `interval_hours` (default 7 days) **AND** `min_idle_hours` (default 2h) elapsed

### Two phases per pass
1. **Automatic transitions** (deterministic, no LLM):
   - `active → stale` after 30 days without usage
   - `stale → archived` after 90 days → moved to `.archive/`

2. **LLM review** (AIAgent fork, max 8 turns, configurable auxiliary model):
   - Reads each skill with `skill_view`
   - Decides: keep / patch / consolidate / archive
   - Mutates via `skill_manage`

### Telemetry `.usage.json`
```json
{
  "my-skill": {
    "use_count": 12,
    "view_count": 34,
    "last_used_at": "2026-04-24T18:12:03Z",
    "patch_count": 3,
    "state": "active",
    "pinned": false
  }
}
```

### Protections
- **Pinning**: absolute protection — blocks even `skill_manage` tool mid-conversation
- **Automatic backup** before each pass → `.curator_backups/`
- **Rollback**: `hermes curator rollback`
- Never touches bundled or hub-installed skills

## 6. External Providers — Detailed Comparison

8 plugins via `hermes memory setup`. Max 1 active alongside builtin.

Automatic behaviors when a provider is active:
- Injects provider context into system prompt
- Prefetch before each turn (non-blocking, background)
- Sync turns after each response
- Extract memories at session end (providers that support it)
- Mirror builtin writes to external provider via `on_memory_write()`

Isolation per profile: each provider isolates data via `$HERMES_HOME/` (differs per profile).

### Comparison Table

| Provider | Storage | Cost | Tools | Dependencies | Unique Feature |
|---|---|---|---|---|---|
| Honcho | Cloud | Paid | 5 | honcho-ai | Dialectic user modeling + session context |
| OpenViking | Self-hosted | Free | 5 | openviking + server | Filesystem hierarchy + tiered loading |
| Mem0 | Cloud | Paid | 3 | mem0ai | Server-side LLM extraction |
| Hindsight | Cloud/Local | Free/Paid | 3 | hindsight-client | Knowledge graph + reflect synthesis |
| Holographic | Local | Free | 2 | None | HRR algebra + trust scoring |
| RetainDB | Cloud | $20/mo | 5 | requests | Delta compression + hybrid search |
| ByteRover | Local/Cloud | Free/Paid | 3 | brv CLI | Pre-compression extraction |
| Supermemory | Cloud | Paid | 4 | supermemory | Context fencing + multi-container |

### Honcho

**Tools (5)**: `honcho_profile`, `honcho_search`, `honcho_context`, `honcho_reasoning`, `honcho_conclude`

Two-layer architecture:
- **Base**: session summary + representation + peer card, refreshed per `contextCadence`
- **Dialectic**: LLM reasoning, per `dialecticCadence`, depth 1–3 via `dialecticDepth`

Automatic cold-start vs warm prompt selection based on whether base context exists.

Multi-profile: shared workspace, global user peer, one AI peer per Hermes profile (`hermes`, `hermes.coder`…). Each AI peer builds an independent representation.

4 observation toggles per peer: `observeMe` / `observeOthers` for user and AI. Preset `directional` (all on) or `unified` (AI models user only).

Config: `$HERMES_HOME/honcho.json` > `~/.hermes/honcho.json` > `~/.honcho/config.json`

- ✅ Most sophisticated user modeling, cross-session, independent multi-profile
- ❌ Paid cloud, config complexity, external dependency

### OpenViking

**Tools (5)**: `viking_search`, `viking_read`, `viking_browse`, `viking_remember`, `viking_add_resource`

- Tiered loading: L0 (~100 tokens) → L1 (~2k) → L2 (full)
- Auto extraction in 6 categories at session commit: profile, preferences, entities, events, cases, patterns
- URI scheme `viking://` for hierarchical navigation

- ✅ Free, self-hosted, structured extraction, browsable like a filesystem
- ❌ Local server required, AGPL-3.0 license

### Mem0

**Tools (3)**: `mem0_profile`, `mem0_search`, `mem0_conclude`

Automatic server-side LLM extraction, semantic search + reranking, automatic deduplication.

- ✅ Zero friction, fully automatic extraction, semantic search
- ❌ Cloud only, paid, no fine control over what gets extracted

### Hindsight

**Tools (3)**: `hindsight_retain`, `hindsight_recall`, `hindsight_reflect`

`hindsight_reflect` = cross-memory synthesis — **unique among all providers**.

- `auto_retain: true`: automatically stores complete turns (tool calls included)
- `recall_budget`: `low` / `mid` / `high` (controls LLM cost)
- Modes: `hybrid` / `context-only` / `tools-only`
- Local mode available (embedded PostgreSQL), local UI via `hindsight-embed`

- ✅ Knowledge graph + entity resolution, only provider with cross-memory reflect/synthesis, local mode
- ❌ Dependency `hindsight-client >= 0.4.22` (auto-upgrade), paid cloud or local infra to manage

### Holographic

**Tools (2)**: `fact_store` (9 actions: add, search, probe, related, reason, contradict, update, remove, list), `fact_feedback`

- Asymmetric trust scoring: +0.05 helpful / -0.10 unhelpful
- `probe`: algebraic recall by entity
- `reason`: AND-compositional multi-entity queries (HRR — Holographic Reduced Representations)
- `contradict`: automatic detection of contradictory facts
- `auto_extract: false` by default

- ✅ 100% local, zero dependencies (SQLite always available), trust scoring, unique HRR algebra, free
- ❌ FTS5 only (no semantic search), NumPy optional for HRR, non-automatic extraction by default

### RetainDB

**Tools (5)**: `retaindb_profile`, `retaindb_search`, `retaindb_context`, `retaindb_remember`, `retaindb_forget`

Hybrid search: Vector + BM25 + Reranking. 7 memory types, delta compression.

- ✅ Advanced hybrid search, delta compression, rich tooling (5 tools)
- ❌ $20/month, cloud only, niche (mainly useful if already a RetainDB customer)

### ByteRover

**Tools (3)**: `brv_query`, `brv_curate`, `brv_status`

**Pre-compression extraction**: extracts insights *before* context compression discards them — only provider that hooks into compression.

- Knowledge tree stored in `$HERMES_HOME/byterover/` (profile-isolated)
- Tiered retrieval: fuzzy text → LLM-driven search
- Optional cloud sync (SOC2 Type II)

- ✅ Local-first, portable (npm CLI), only provider with pre-compression hook, free locally
- ❌ `brv` CLI required, limited tooling (3 tools), paid cloud sync

### Supermemory

**Tools (4)**: `supermemory_store`, `supermemory_search`, `supermemory_forget`, `supermemory_profile`

- **Automatic context fencing**: recalled memories are stripped from captured turns → prevents recursive pollution
- Session-end ingest for knowledge graph-level building
- Profile facts injected at 1st turn and every N turns (`profile_frequency: 50`)
- Trivial message filtering (skips "ok", "thanks"…)
- Multi-container: `{identity}` template for profile isolation, `enable_custom_container_tags` for cross-container read/write

- ✅ Unique context fencing, flexible multi-container, graph-level session ingest
- ❌ Cloud only, paid, `api_timeout: 5.0s` can slow turns

## 7. Prompt Assembly — Layer Order

Exact system prompt construction order at each session:

```
1. SOUL.md (or hardcoded DEFAULT_AGENT_IDENTITY)
2. Guidance blocks (MEMORY_GUIDANCE, SESSION_SEARCH_GUIDANCE, SKILLS_GUIDANCE)
3. Provider static block (e.g. Honcho base context)
4. Frozen MEMORY.md snapshot
5. Frozen USER.md snapshot
6. Skills index (Level 0 — ~3k tokens)
7. Context files (AGENTS.md, .cursorrules, CLAUDE.md, .hermes.md)
8. Timestamp + session ID + platform hint
```

Kept **outside** the cached prefix (injected per API call):
- Prefetch recall `<memory-context>` (scrubbed by `StreamingContextScrubber`)
- Ephemeral gateway overlays
- Honcho dialectic recall

Context files — priority (first match wins):
1. `.hermes.md` / `HERMES.md` → walks up to git root
2. `AGENTS.md` → CWD only
3. `CLAUDE.md` → CWD only
4. `.cursorrules` / `.cursor/rules/*.mdc` → CWD only

All scanned for injection attacks (invisible unicode, "ignore previous instructions", credential exfiltration), truncated to 20k chars.

## Summary

| Layer | Technology | Capacity | Persistence |
|---|---|---|---|
| MEMORY.md | Markdown file | 2200 chars | Cross-session |
| USER.md | Markdown file | 1375 chars | Cross-session |
| Session Search | SQLite FTS5 + LLM | Unlimited | Cross-session |
| Skills | SKILL.md files | Unlimited | Cross-session |
| External provider | Plugin (Honcho, Mem0…) | Variable | Cross-session |

Full loop: agent **learns** (memory tool) → **generalizes** (skill_manage) → **consolidates** (curator in background) → **retrieves** (session_search on-demand).

---

## Architectural Review

### Strengths

- **Declarative / procedural / episodic separation.** MEMORY.md = semantic memory (facts), Skills = procedural memory (know-how), Session Search = episodic memory (events). Right taxonomy, each layer gets an adapted management mechanism.
- **Frozen snapshot for prefix cache.** Accepting that memory writes are only visible next session is a good trade-off — modifying the system prompt mid-session invalidates provider-side cache.
- **Progressive disclosure of skills.** Level 0 index (~3k tokens) in system prompt, drill-down on-demand. Lazy loading applied to prompting — scales well.
- **Curator as skill GC.** Deterministic transitions (stale → archived) + LLM review pass for consolidation. Pinning and rollback show anticipation of drift.
- **ByteRover's pre-compression hook.** Extracting insights before context compression destroys them is a rare architectural insight.
- **Skill creation triggers.** ≥5 tool calls + success, user correction, or errors overcome — high-value moments for procedural knowledge capture, not arbitrary.
- **Deterministic lifecycle decoupled from LLM review.** 30 days unused = stale, 90 days = archived. No LLM needed for that. LLM pass comes on top for intelligent consolidation.

### Weaknesses

- **2200/1375 char caps are too low.** ~1300 tokens total for persistent memory. A developer working on 3 projects saturates this in a day. Could be dynamic based on model context window.
- **Agent self-manages its memory.** Quality depends entirely on MEMORY_GUIDANCE adherence. LLMs are inconsistent at memorization discipline — no automatic safety net (unless opting into an external provider).
- **Max 1 external provider.** Presented as architectural choice, but it's an implementation simplification. Holographic (local, trust scoring) and Honcho (cloud user modeling) are complementary, not substitutable.
- **No semantic search in builtin.** FTS5 = keyword matching only. "How to deploy the service" won't find a session about "mise en production de l'API". 90% of users use the default path — and it has no semantic recall.
- **Curator is a cheap LLM judging a capable LLM's work.** Risk: archiving valid skills or introducing regressions via patches. Backup/rollback mitigates but detection is retroactive.
- **No multi-user shared memory.** Everything in `~/.hermes/` — single user. No mechanism to share skills or memories across a team working on the same project.
- **Substring matching for replace/remove is fragile.** Intrinsically brittle compared to ID-based or index-based systems.
- **Context fencing only in Supermemory (paid plugin).** Builtin has no protection against recursive memory pollution in session_search recall.

### Self-learning Loop — Specific Analysis

The full cycle: complex task succeeded → `skill_manage create` → skill reused in future sessions → telemetry tracks usage → Curator reviews (max 8 turns, cheap model) → patch / consolidate / archive → refined skills available next session.

**Architecturally coherent but epistemically closed.** The agent learns from itself, judges itself, corrects itself. Works as long as the underlying LLM has sufficient judgment capacity. But this is exactly the type of system that can converge to a local optimum without external perturbation.

Key gaps in the loop:
- **No human validation at skill creation.** If the agent solves a problem sub-optimally, it crystallizes a bad practice into a reusable skill.
- **Curator has no feedback loop of its own.** Nobody curates the Curator. If a curation pass introduces a regression, the only detection is the user noticing degraded behavior after the fact.
- **8 turns max for Curator is insufficient.** With 30 active skills, that's ~0.25 turns per skill. Consolidation (merging two overlapping skills) alone consumes 2-3 turns.
- **No structured user feedback.** User can pin (protect) but cannot signal "this skill is bad" or "this skill helped". The only signal is passive usage (`use_count`). The learning loop is entirely self-referential.

### Relevance for Coday

Directly applicable:
- Frozen snapshot pattern (already similar in Coday memories)
- Progressive disclosure for skills/memories
- Deterministic lifecycle management (stale → archive) for knowledge maintenance

Not applicable:
- Single-user local architecture (`~/.hermes/`) vs Coday's multi-tenant server-side model
- Max 1 provider limit vs Coday's extensibility goals
- No semantic search in builtin — non-starter for professional use

Open question for Coday: if we implement procedural memory (skills), where do we inject human feedback into the loop? Not just rollback after the fact, but active signals — "this skill is good", "this skill misled me", "merge these two".
