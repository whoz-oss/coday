# Product Vision

## Context

Coday is a lightweight framework to use AI agents on existing scoped projects, with as much autonomy as wanted through
contextual understanding and tool integration. It runs locally and interfaces with various APIs and tools to provide a
comprehensive assistance experience, or even full-autonomous work capability.

AI is here for good but not autonomous enough to be let go on existing projects without significant preparation. Coday's
aim is to target directly the endgoal of agents and not view them as tool extensions for manual directions, but as tool
users for light supervision.

By focusing on connecting advanced AI models, existing tools and project knowledge, Coday can stay relevant at low cost
in an ever accelerating software landscape. Through its multi-agent architecture, it leverages both project-specific
agents and internal technical agents to provide sophisticated capabilities while maintaining operational simplicity.

## Goals

1. Future proofing AI use on projects
    - Leverage state-of-the-art (SOTA) models to benefit naturally from their future capabilities
    - No strings attached to existing software
    - Simple everlasting interfaces: terminal chat, web chat
   - Support for diverse agent types and team collaboration
   - Integration with specialized platform capabilities

2. Tool Enhancement
    - Extend AI capabilities through well-defined tools
    - Context-aware operations
    - Tooling tailored to project's current possibilities

3. Contextual Intelligence
    - Understanding of project structure
    - Memory of past interactions
    - Learning on the way through memorization
   - Intelligent task routing through specialized agents
   - Team-based problem-solving capabilities

## Non-Goals

- Not a replacement for existing IDE features
- Not a project management system
- Not a deployment platform

## AgentOS Evolution

**AgentOS** is the strategic backend (Spring Boot + Kotlin + Spring AI) that will replace Coday's current Node.js server entirely. It is already used in production at Whoz and is the target platform for all future server-side development. The Node.js Express server (`apps/server`) is a prototype that will be retired once AgentOS reaches feature parity.

- **Coday** (`libs/`, `apps/client`): the core runtime, tool implementations, and Angular frontend — these remain and are not replaced
- **AgentOS** (`agentos/`): replaces `apps/server`; provides production-grade orchestration, multi-tenancy, plugin system (PF4J), and REST+SSE API
- **Transition**: both coexist during development; the Angular client already proxies `/api/agentos/*` to AgentOS; new server-side features should be built in AgentOS, not in the Express server

All tool integrations and agent capabilities currently implemented in the Node.js layer are to be progressively migrated or re-implemented in AgentOS plugins.

### How the roadmap works

There is no static roadmap file — the backlog lives in GitHub issues and milestones. When evaluating priorities or planning a sprint, PM should:
- Browse open issues filtered by milestone or label on GitHub
- Use the `list_issues` and `search_issues` tools to get a current snapshot
- Use Jira for sprint-level tracking and velocity; GitHub for feature-level detail and PR linkage

This keeps the roadmap as the single source of truth and avoids drift between a doc and the actual state of work.

### Key design targets for AgentOS

**Multi-user from the start.** A case (conversation thread) can involve multiple human actors. The `Actor` model is already in the event hierarchy; the HTTP layer and SSE routing need to follow. Each browser session should eventually have its own user-scoped SSE channel, with a subscription model that fans case events to the right sessions. This is a hard architectural concern — retrofitting multi-user later is expensive.

**Flexible agent scoping.** Agents should not be locked to a single project namespace. The target model allows agent scope at multiple levels: user (a personal twin that spans projects), namespace/project (current model), and case (ephemeral). This enables cross-project agent access and a user having a personal agent space that can reach into their project namespaces.

**Delegation as a first-class pattern.** Agent-to-agent delegation (spawning sub-cases, sync or async, with result routing) is central to multi-agent orchestration. The design must handle headless sub-cases (no user on SSE), result propagation back to the parent, and stack depth limits. Interactive questions in headless contexts must fail fast rather than block indefinitely.

**Namespace hierarchies.** Worktrees and sub-projects suggest a natural parent/child namespace relationship. The data model should leave room for namespace trees and cross-namespace agent visibility without committing to a specific topology prematurely.