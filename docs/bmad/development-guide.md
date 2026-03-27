# Development Guide — Coday

## Prerequisites

- **Node.js:** 22+
- **pnpm:** Package manager (workspaces enabled)
- **API Keys:** ANTHROPIC_API_KEY and/or OPENAI_API_KEY as environment variables
- **NX:** Installed as devDependency (no global install needed)

## Installation

```bash
# Clone the repository
git clone https://github.com/whoz-oss/coday.git
cd coday

# Install dependencies
pnpm install
```

Post-install automatically runs husky for git hooks setup.

## Running the Application

### Web Interface (Development)

```bash
# Start both client and server in parallel
pnpm web:dev
# This runs: nx run-many --target=serve --projects=client,server --parallel=2
```

The web UI will be available at http://localhost:3000 (or similar).

### Individual Services

```bash
# Angular frontend only
pnpm client    # nx run client:serve

# Express API server only
pnpm server    # nx run server:serve

# Electron desktop app
pnpm desktop   # nx run desktop:serve
```

### Published Package (User Mode)

```bash
# Run the published version
npx --yes @whoz-oss/coday-web
```

## Build Commands

```bash
# Build a specific project
nx build <project-name>

# Build all projects
nx run-many --target=build
```

## Testing

```bash
# Run all tests
pnpm test

# Run tests for a specific library
nx test <lib-name>

# Run a single test file
nx test <lib-name> --testFile=<filename>

# Run linting
pnpm lint
```

- **Unit Tests:** Jest 30.2.0 — located alongside source files as *.spec.ts or *.test.ts
- **E2E Tests:** Playwright 1.56.0 — located in apps/client-e2e/

## Code Style and Conventions

- **No semicolons** — enforced by Prettier
- **Single quotes** — enforced by Prettier
- **120 character line limit**
- **Imports:** Use absolute paths with @coday/* aliases
- **Naming:**
  - camelCase for variables and methods
  - PascalCase for classes, interfaces, types
  - kebab-case for file names
- **Commit Messages:** Conventional commits enforced by commitlint + husky
  - Format: `type(scope): description`
  - Types: feat, fix, chore, refactor, docs, test, etc.

## Project Structure

This is an NX monorepo with pnpm workspaces:

- **apps/** — Application targets (client, server, desktop, web, client-e2e)
- **libs/** — Shared TypeScript libraries (17+ packages)
- **agentos/** — Separate Kotlin/Gradle subsystem
- **prompts/** — Prompt templates
- **migrations/** — Data migration scripts
- **scripts/** — Build and utility scripts

### Adding a New Library

```bash
nx g @nx/js:library <lib-name> --directory=libs/<lib-name>
```

Libraries are imported via @coday/<lib-name> path aliases configured in tsconfig.base.json.

## Dependency Management

pnpm workspace catalogs are used for centralized version management:

```yaml
# pnpm-workspace.yaml
catalog:
  '@anthropic-ai/sdk': 0.65.0
  express: 5.1.0
  # ...
```

Reference in package.json:
```json
{
  "dependencies": {
    "@anthropic-ai/sdk": "catalog:"
  }
}
```

Sync dependencies across projects:
```bash
pnpm sync-deps   # tsx scripts/sync-project-dependencies.ts
```

## CI/CD Pipeline

- **.github/workflows/validate.yml** — Runs on PRs: lint, test, build validation
- **.github/workflows/release.yml** — Automated release: versioning, changelog, npm publish

## Configuration

- **coday.yaml** — Project-level configuration (agent definitions, scripts, docs)
- **nx.json** — NX workspace settings (caching, task defaults)
- **tsconfig.base.json** — Shared TypeScript compiler options and path aliases
- **eslint.config.js** — Linting rules
- **jest.config.ts** — Root test configuration
- **jest.preset.cjs** — Shared Jest preset for all projects

## AgentOS Subsystem

The agentos/ directory contains a separate Kotlin/Gradle project:

```bash
cd agentos

# Build
./gradlew build

# Run with Docker
docker-compose up
```

See agentos/README.md and agentos/QUICKSTART.md for details.

## Existing Documentation

User-facing documentation is in docs/:
- docs/01-introduction/ — What is Coday
- docs/02-getting-started/ — Installation, launching, first conversation
- docs/03-using-coday/ — Interface, agents, conversations
- docs/04-configuration/ — Configuration levels, agents, MCP, prompts, schedulers
- docs/05-working-effectively/ — Agent design, prompting, memory, debugging
- docs/06-guides/ — Specific guides (AI models, debugging, refactoring)
- docs/features/ — Feature-specific docs
- docs/to-rework/ — Legacy docs being updated
