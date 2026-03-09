# Glossary

Canonical definitions for terms used across the project. When Coday uses a different name for the same concept, it is noted in parentheses.

---

**Namespace** *(Coday: Project)*
The top-level organizational unit that groups cases together and defines the operational context: available agents, tools, integrations, and shared memory. In Coday, declared as `coday.yaml` in the project root.

**User**
A human operator identified by a username. Has personal configuration, preferences, and memory that persist across sessions.

**Case** *(Coday: Thread)*
A single conversation session between a user and one or more agents. Holds the ordered sequence of events that make up the interaction history. Can reference other cases/threads as sub-cases/sub-threads.

**CaseEvent** *(Coday: CodayEvent)*
An atomic, immutable unit of information emitted during a case: a message, a tool call, a thinking indicator, a question, etc. The full ordered list of events on a case constitutes its history.

**Agent**
An AI capability unit configured with a model, instructions, and a set of tools. Agents are scoped to a namespace and addressable by name (e.g. `@agent-name`).

**Tool**
A callable capability exposed to an agent to interact with the environment: file system operations, git commands, external API calls, etc. 

**Integration** *(no direct AgentOS equivalent yet)*
A group of related tools and configuration that enables interaction with a specific external system (GitHub, Jira, Slack, etc.). Declared in project or user configuration.

**MCP (Model Context Protocol)**
An open standard protocol for connecting AI models to external tools and data sources. Supported as an integration type, allowing any MCP-compatible server to expose tools to agents.
