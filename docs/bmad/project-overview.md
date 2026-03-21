# Project Overview: Coday

## Summary

Coday is a lightweight, conversation-based agentic framework for AI-assisted development. Users interact with AI agents through a web interface or terminal, with support for multiple AI providers including OpenAI, Anthropic, and Google Generative AI.

- **License:** MIT
- **Author:** vincent.audibert@whoz.com
- **Repository:** https://github.com/whoz-oss/coday

## Repository Type

NX-based TypeScript monorepo managed with pnpm workspaces.

## Tech Stack Summary

| Category | Technology | Version |
|----------|-----------|---------|
| Language | TypeScript | 5.9.3 |
| Frontend Framework | Angular | 20.3.4 |
| Backend Framework | Express | 5.1.0 |
| Desktop | Electron | 38.3.0 |
| Build System | NX | 22.2.3 |
| Package Manager | pnpm (workspaces) | - |
| Testing | Jest 30.2.0, Playwright 1.56.0 | - |
| AI SDKs | @anthropic-ai/sdk 0.65.0, openai 6.16.0, @google/generative-ai 0.24.1 | - |
| MCP | @modelcontextprotocol/sdk 1.23.1 | - |
| AgentOS Backend | Kotlin/Gradle | - |
| CI/CD | GitHub Actions (release.yml, validate.yml) | - |

## Architecture

Coday follows a multi-part monorepo structure with a layered architecture.

### Applications

- **apps/client** -- Angular 20 SPA serving as the web UI for conversations.
- **apps/server** -- Express 5 API responsible for managing threads, agents, and configurations.
- **apps/desktop** -- Electron wrapper around the web client for native desktop usage.
- **apps/web** -- Published npm package (`@whoz-oss/coday-web`) for easy distribution.
- **apps/client-e2e** -- Playwright end-to-end tests for the client application.

### AgentOS

- **agentos/** -- Kotlin/Gradle service with its own SDK, plugins, and Docker setup.

### Shared Libraries

The `libs/` directory contains 17+ shared TypeScript libraries organized by domain:

- **Core domains:** agent, core, model, service, handler, function, utils
- **Infrastructure:** mcp, repository, design-system
- **Integration adapters:** git, gitlab, jira, slack, confluence, basecamp, zendesk-articles, ai, file, http

### Specialized Handlers

- **libs/handlers/** -- Message handlers for specific concerns: config, load, looper, memory, openai, stats.

### AgentOS Frontend Libraries

- **libs/agentos-*** -- Frontend libraries for AgentOS integration: api-client, dataflow, ui.

## Key Features

- Conversation-based AI agent interaction
- Multiple AI provider support (OpenAI, Anthropic, Google)
- MCP (Model Context Protocol) integration
- Custom agent definitions with project-scoped configuration
- Memory and context system for persistent knowledge
- Extensive integration ecosystem (Git, GitLab, Jira, Slack, Confluence, etc.)
- Web, terminal, and desktop interfaces
- Scheduler support for automated tasks
- Webhook API for external integrations

## Links to Detailed Docs

- [Architecture](./architecture.md)
- [Source Tree Analysis](./source-tree-analysis.md)
- [Development Guide](./development-guide.md)
- Existing user docs: `../01-introduction/` through `../06-guides/`
