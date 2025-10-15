# Coday Codebase Guidelines

## Build & Test Commands
```
pnpm start           # Run terminal app
pnpm web             # Run web interface
pnpm lint            # Run linting on all packages
pnpm test            # Run all tests
nx test <lib-name>   # Run tests for specific lib
nx test <lib-name> --testFile=<filename>  # Run single test
```

## Code Style
- **Imports**: Use absolute paths with aliases (@coday/*)
- **Formatting**: No semicolons, single quotes, 120 char line limit
- **Types**: TypeScript with strict mode, explicit return types on functions
- **Naming**:
  - Use camelCase for variables, methods
  - Use PascalCase for classes, interfaces, types
  - Use kebab-case for files
- **Error Handling**: Prefer using explicit error handling with try/catch
- **Testing**: Jest with descriptive test blocks and specific assertions

## Architecture
- NX-based monorepo with libs/ for shared code and apps/ for applications
- Use dependency injection pattern when possible
- Files should have single responsibility
- Prefer functional programming patterns over inheritance
