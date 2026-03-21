# Source Tree Analysis — Coday

## Overview
NX monorepo with pnpm workspaces. Primary code in apps/ and libs/, with a separate agentos/ Kotlin subsystem.

## Annotated Directory Tree

```
coday/
├── apps/                          # Application targets
│   ├── client/                    # Angular 20 web frontend (SPA)
│   │   ├── src/
│   │   │   ├── app/               # Angular app module, components, routing
│   │   │   ├── main.ts            # [ENTRY] Angular bootstrap
│   │   │   └── styles.scss        # Global styles
│   │   ├── public/                # Static assets (logos, icons)
│   │   └── docs/                  # Client-specific docs
│   ├── server/                    # Express 5 API backend
│   │   └── src/
│   │       ├── server.ts          # [ENTRY] Express server bootstrap
│   │       └── lib/               # Route handlers and server logic
│   │           ├── agent.routes.ts
│   │           ├── config.routes.ts
│   │           ├── message.routes.ts
│   │           ├── project.routes.ts
│   │           ├── prompt.routes.ts
│   │           ├── prompt-execution.routes.ts
│   │           ├── scheduler.routes.ts
│   │           ├── thread.routes.ts
│   │           ├── token-usage.routes.ts
│   │           ├── user.routes.ts
│   │           ├── thread-coday-manager.ts
│   │           ├── thread-post-processor.ts
│   │           ├── route-helpers.ts
│   │           ├── coday-options-utils.ts
│   │           ├── find-available-port.ts
│   │           └── log.ts
│   ├── desktop/                   # Electron desktop wrapper
│   │   ├── src/
│   │   │   ├── main.ts            # [ENTRY] Electron main process
│   │   │   ├── preload.ts         # Electron preload script
│   │   │   └── loader.html        # Electron loader page
│   │   ├── assets/                # Desktop-specific assets
│   │   └── macos/                 # macOS-specific configuration
│   ├── web/                       # Published npm package distribution
│   └── client-e2e/                # Playwright E2E test suite
│       └── src/                   # E2E test specs
├── libs/                          # Shared TypeScript libraries
│   ├── core/                      # Core domain types, base classes
│   ├── model/                     # Data models and types
│   ├── agent/                     # Agent definition and lifecycle
│   ├── service/                   # Service layer abstractions
│   ├── handler/                   # Message handler base
│   ├── handlers/                  # Specialized handler implementations
│   │   ├── config/                # Configuration handler
│   │   ├── load/                  # Document/data loading handler
│   │   ├── looper/                # Loop/iteration handler
│   │   ├── memory/                # Memory/context handler
│   │   ├── openai/                # OpenAI-specific handler
│   │   └── stats/                 # Usage statistics handler
│   ├── function/                  # Function/tool definitions
│   ├── integration/               # Integration base framework
│   ├── integrations/              # Integration adapters
│   │   ├── ai/                    # AI provider integration
│   │   ├── git/                   # Git operations
│   │   ├── gitlab/                # GitLab API
│   │   ├── jira/                  # Jira API
│   │   ├── slack/                 # Slack API
│   │   ├── confluence/            # Confluence API
│   │   ├── basecamp/              # Basecamp API
│   │   ├── zendesk-articles/      # Zendesk Articles API
│   │   ├── file/                  # File system operations
│   │   └── http/                  # HTTP client
│   ├── mcp/                       # Model Context Protocol support
│   ├── repository/                # Data persistence layer
│   ├── coday-services/            # High-level Coday service orchestration
│   ├── utils/                     # Shared utilities
│   ├── design-system/             # UI component library / design tokens
│   ├── agentos-api-client/        # AgentOS REST API client
│   ├── agentos-dataflow/          # AgentOS data flow management
│   └── agentos-ui/                # AgentOS UI components
├── agentos/                       # Kotlin/Gradle backend subsystem
│   ├── agentos-service/           # Main Kotlin service (with Dockerfile)
│   ├── agentos-sdk/               # AgentOS SDK for plugin development
│   ├── agentos-datetime-plugin/   # DateTime plugin
│   ├── agentos-plugins-filesystem/ # Filesystem plugin
│   ├── aimodel/                   # AI model abstractions
│   ├── aiprovider/                # AI provider implementations
│   ├── plugins/                   # Plugin system
│   ├── openapi/                   # OpenAPI spec definitions
│   ├── postman/                   # Postman collections
│   ├── docker-compose.yml         # Docker orchestration
│   └── scripts/                   # Build/run scripts
├── prompts/                       # Prompt templates
├── migrations/                    # Data migration scripts
│   └── local/                     # Local environment migrations
├── scripts/                       # Build and utility scripts
├── .github/workflows/             # CI/CD pipelines
│   ├── release.yml                # Release automation
│   └── validate.yml               # Validation checks
├── coday.yaml                     # Project-level Coday configuration
├── nx.json                        # NX workspace configuration
├── pnpm-workspace.yaml            # pnpm workspace definition + catalog
├── tsconfig.base.json             # Shared TypeScript configuration
├── jest.config.ts                 # Root Jest configuration
├── eslint.config.js               # ESLint configuration
├── commitlint.config.ts           # Commit message linting
└── package.json                   # Root package manifest
```

## Critical Folders

| Folder | Purpose | Part |
|--------|---------|------|
| apps/client/src/app/ | Angular SPA — all UI components and routing | client |
| apps/server/src/lib/ | Express routes — all API endpoints | server |
| apps/desktop/src/ | Electron main process and preload | desktop |
| libs/core/src/ | Core domain types shared across all apps | shared |
| libs/model/src/ | Data model definitions | shared |
| libs/agent/src/ | Agent lifecycle and definition | shared |
| libs/service/src/ | Service layer abstractions | shared |
| libs/handler/src/ | Message handler base classes | shared |
| libs/handlers/ | All specialized handler implementations | shared |
| libs/integrations/ | All third-party integration adapters | shared |
| libs/mcp/src/ | MCP protocol support | shared |
| libs/repository/src/ | Data persistence | shared |
| agentos/agentos-service/ | Kotlin backend service | agentos |

## Entry Points

| Entry Point | File | Description |
|-------------|------|-------------|
| Web Frontend | apps/client/src/main.ts | Angular bootstrap |
| API Server | apps/server/src/server.ts | Express server startup |
| Desktop App | apps/desktop/src/main.ts | Electron main process |
| AgentOS Service | agentos/agentos-service/ | Kotlin application |
| CLI / npx | apps/web/ | Published npm package entry |

## Integration Points (Cross-Part)

| From | To | Mechanism |
|------|----|-----------|
| client | server | HTTP REST API calls |
| desktop | client | Electron loads Angular SPA |
| server | libs/* | Direct TypeScript imports via @coday/* aliases |
| client | libs/design-system | UI component imports |
| client | libs/agentos-ui | AgentOS UI components |
| server | libs/integrations/* | Integration adapters for external services |
| server | libs/mcp | MCP protocol handling |
| libs/agentos-api-client | agentos-service | REST API calls to Kotlin backend |
