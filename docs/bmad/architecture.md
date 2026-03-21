# Architecture — Coday

## Executive Summary

Coday is a conversation-based agentic framework built as an NX monorepo. It follows a layered architecture with a clear separation between the Angular frontend (client), Express API backend (server), Electron desktop wrapper, and a Kotlin-based AgentOS backend service. Shared logic lives in 17+ TypeScript libraries under libs/, accessed via @coday/* path aliases.

## Architecture Pattern

**Primary:** Layered monorepo with service-oriented backend
- **Presentation Layer:** Angular 20 SPA (apps/client) + Electron wrapper (apps/desktop)
- **API Layer:** Express 5 REST API (apps/server) with route-based organization
- **Service Layer:** libs/service, libs/coday-services — orchestration and business logic
- **Agent Layer:** libs/agent, libs/handler, libs/handlers/* — AI agent lifecycle and message handling
- **Integration Layer:** libs/integration, libs/integrations/* — external service adapters
- **Data Layer:** libs/model, libs/repository — domain models and persistence
- **Protocol Layer:** libs/mcp — Model Context Protocol support
- **AgentOS Subsystem:** agentos/ — Kotlin/Gradle service with its own SDK and plugin system

## Technology Stack

| Category | Technology | Version | Justification |
|----------|-----------|---------|---------------|
| Language | TypeScript | 5.9.3 | Primary language for all TS parts |
| Language | Kotlin | - | AgentOS backend service |
| Frontend | Angular | 20.3.4 | SPA framework for web UI |
| Backend | Express | 5.1.0 | REST API server |
| Desktop | Electron | 38.3.0 | Cross-platform desktop wrapper |
| Build | NX | 22.2.3 | Monorepo build orchestration |
| Packages | pnpm (workspaces + catalog) | - | Dependency management |
| Unit Test | Jest | 30.2.0 | Unit testing framework |
| E2E Test | Playwright | 1.56.0 | End-to-end testing |
| AI: Anthropic | @anthropic-ai/sdk | 0.65.0 | Claude API integration |
| AI: OpenAI | openai | 6.16.0 | OpenAI/GPT API integration |
| AI: Google | @google/generative-ai | 0.24.1 | Gemini API integration |
| MCP | @modelcontextprotocol/sdk | 1.23.1 | Model Context Protocol |
| HTTP | axios | 1.12.2 | HTTP client for integrations |
| Streaming | rxjs | 7.8.2 | Reactive programming |
| Build: AgentOS | Gradle | - | Kotlin build system |

## API Design (Quick Scan)

The Express server (apps/server/src/lib/) exposes REST endpoints organized by domain:

| Route File | Domain | Likely Endpoints |
|------------|--------|------------------|
| agent.routes.ts | Agent management | CRUD for agents |
| config.routes.ts | Configuration | Project/user config |
| message.routes.ts | Messages | Send/receive messages |
| project.routes.ts | Projects | Project CRUD |
| prompt.routes.ts | Prompts | Prompt templates |
| prompt-execution.routes.ts | Execution | Run prompts |
| scheduler.routes.ts | Schedulers | Scheduled tasks |
| thread.routes.ts | Threads | Conversation threads |
| token-usage.routes.ts | Token usage | Usage statistics |
| user.routes.ts | Users | User management |

Supporting utilities: route-helpers.ts, coday-options-utils.ts, thread-coday-manager.ts, thread-post-processor.ts.

## Component Overview

### Shared Libraries (@coday/*)

| Library | Purpose |
|---------|---------|
| @coday/core | Core domain types, base classes, constants |
| @coday/model | Data models and type definitions |
| @coday/agent | Agent definition, lifecycle management |
| @coday/service | Service layer abstractions |
| @coday/handler | Base message handler framework |
| @coday/function | Tool/function definitions for AI agents |
| @coday/integration | Integration base framework and interfaces |
| @coday/mcp | Model Context Protocol implementation |
| @coday/repository | Data persistence and storage |
| @coday/coday-services | High-level service orchestration |
| @coday/utils | Shared utility functions |
| @coday/design-system | UI components and design tokens |

### Integration Adapters (libs/integrations/*)

| Adapter | External Service |
|---------|-----------------|
| ai | AI provider abstraction layer |
| git | Local git operations |
| gitlab | GitLab REST API |
| jira | Jira REST API |
| slack | Slack API |
| confluence | Confluence API |
| basecamp | Basecamp API |
| zendesk-articles | Zendesk Articles API |
| file | File system operations |
| http | Generic HTTP client |

### Handler Implementations (libs/handlers/*)

| Handler | Responsibility |
|---------|---------------|
| config | Configuration management |
| load | Document/data loading |
| looper | Loop/iteration control |
| memory | Persistent memory/context |
| openai | OpenAI-specific message handling |
| stats | Usage statistics tracking |

### AgentOS Subsystem (agentos/)

Separate Kotlin/Gradle backend with:
- agentos-service: Main service (Dockerized)
- agentos-sdk: Plugin development SDK
- Plugins: datetime, filesystem
- AI model/provider abstractions
- OpenAPI specs and Postman collections

Frontend integration via:
- libs/agentos-api-client: REST client for the Kotlin service
- libs/agentos-dataflow: Data flow management
- libs/agentos-ui: Angular UI components

## Data Architecture

Quick scan identified:
- **migrations/** directory with local/ subfolder — indicates a migration-based data evolution strategy
- **libs/repository/** — persistence layer (likely file-based or local storage given the project's nature as a developer tool)
- **libs/model/** — domain model definitions
- No traditional database schema files detected (no Prisma, no SQL migrations) — suggests file-system-based persistence

## Testing Strategy

| Type | Framework | Location |
|------|-----------|----------|
| Unit Tests | Jest 30.2.0 | *.spec.ts / *.test.ts in each lib |
| E2E Tests | Playwright 1.56.0 | apps/client-e2e/ |
| Linting | ESLint + Prettier | eslint.config.js |
| Commit Lint | commitlint (conventional) | commitlint.config.ts |

Commands:
- `pnpm test` — Run all tests
- `pnpm lint` — Run linting
- `nx test <lib-name>` — Test specific library

## Deployment Architecture

- **GitHub Actions CI/CD:**
  - validate.yml — PR validation (lint, test)
  - release.yml — Automated release pipeline
- **npm Publishing:** apps/web published as @whoz-oss/coday-web
- **Docker:** agentos/agentos-service has Dockerfile + docker-compose.yml
- **Desktop:** Electron Builder for desktop distribution

## Development Workflow

- NX-based task execution (build, test, lint, serve)
- pnpm workspace with catalog for centralized dependency versions
- Conventional commits enforced via commitlint + husky
- Path aliases via @coday/* for clean imports
- Automated release pipeline
