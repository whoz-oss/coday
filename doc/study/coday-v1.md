# Coday V1

## Rex until now

### Painful learnings
- OpenAI assistant API was a trap, context = thread management is core to the application, it should be owned completely
- Combining config commands and ai commands is a mess, thread is defaced into an identity-changing thing that brings a whole class of complexity and bugs. Web UI is corrupted by this unclear boundary
- File-based structure got it far and running locally. Does not scale. Needs more leeway from 1 thread + events = 1 file.

### Positive choices
- Bet on agents every day, give them space and tools to "breathe" on their tasks
- Chat/thread/conversation is a natural form of interaction that happens to match very nicely the LLM APIs
- Configuration at `coday.yaml`, project and user level proved very handy, needs to survive the DB move (and still work locally).
- MCP are here to stay.
- Agents act on behalf of the user or through their own account, no middle ground.
- Events that bubble to the web UI (if passed along) carry meaning, data and are simple enough. TS in front & back was useful.

## Vision

Convergence with agentic framework at Whoz through a common core in the backend, and reusable frontend components from Coday. Parts in Coday should stay:

- simple
- extensible/overrideable
- pretty enough
- usable locally (files, no/less auth, light infra) as deployed (DB, full auth, ...)

Coday should become a robust agentic framework supporting entreprise processes.

## Details

### Entities

- User: one person, can work on several projects
- Project: context in which configurations are resolved into some agents and tools
- Thread: anchor for chronological events, attached to a project or other threads
- Event: markers for stuff that happened (user request, agent answer, tool call/response, summary, sub-thread, ...)
- Agent: embodiement of instructions and tools
- Tool: function, internal or external, MCPs, ...

### UI

Web-based chat UI, with openings for Teams, Slack and others.

#### Pages

- login
- homepage: "hello", short list of unread threads, textarea input to start a new thread, links to list of threads, userConfig, admin
- list of threads
- thread: shows past events, current status of the thread, links to parent thread or sub-threads in the timeline.
- userConfig: let the user do what he can (bio, preferred agent, dark/light theme, language, etc...)
- admin: to administrate the project or the platform, see stats of agents, users, webhooks

### API

REST endpoints for entities.

Nested entities and actions through nested paths `/api/thread/{threadId}/event/{eventId}/do-something`

SSE or WS for back to front, mainly to subscribe to a given thread.

### Core

The Thread:
- maintains the chronological timeline of past events.
- exposes a flow/stream of events when instantiated (that may or may not be stored internally)
- is self-sufficient to `.run()` and decide what to do between waiting for a user request, calling an agent, or executing tools (requiring rich instance with AgentService, EventService references) and looping on an agent.
- has a status: running (spewing out events), stopped (waiting for user input or async tool responses or webhook triggers). Other status not relevant ?
- should be instantiated when needed, then kept in a cache to avoid the overhead of re-hydrating all the events, agents, tools.

The Agent:
- is instanciated as an entity with resolved instructions (including context, docs, memories) and tool references (from its integrations and available toolsets/tools)
- can `.run(Thread)` and emits events (text chunks, agent response, tool calls) corresponding to one LLM API call

Questions:
- how to handle the async tools like an agent wanting to wait until tomorrow ? Post a scheduleEvent then stop the Thread ?
- tools do need references to the context, here being the Thread for at least referencing it (or creating a sub-Thread, so reference to ThreadService needed).

### Visibility

Entities are visible only by certain users, either explicitly through their Id, or implicitly through groups. 

Questions: how are groups defined, attached to ? Belong to project, made an entity ?

```
visibility: {
    groups: [groupId1, groupId2], // only users belonging to these groups can see
    users: [userId1, userId2] // ...as well as these specific users whatever their groups
}
```