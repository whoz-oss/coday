# Development Guidelines

## Architectural Principles

[Previous architectural principles section]

## Code Guidelines

1. Core Principles
   - Keep it simple over clever
   - Document public interfaces
   - Handle errors explicitly
   - Use TypeScript features appropriately

2. TypeScript Best Practices
   - **Use nullish coalescing (`??`)** instead of logical OR (`||`) for default values
     ```typescript
     // Preferred: Only replaces null/undefined
     const value = apiResponse ?? 'default'

     // Avoid: Replaces all falsy values (0, '', false, etc.)
     const value = apiResponse || 'default'
     ```
   - Use optional chaining (`?.`) for safe property access
   - Leverage TypeScript's type system for compile-time safety

3. Angular Best Practices (client app)
   - **Use modern control flow syntax** (Angular 17+) instead of structural directives
     ```typescript
     // Preferred: @if control flow
     @if (condition) {
       <div>Content</div>
     }

     // Avoid: *ngIf structural directive
     <div *ngIf="condition">Content</div>
     ```
   - **Use @for with explicit tracking** for lists
     ```typescript
     // Preferred: @for with track
     @for (item of items; track item.id) {
       <div>{{ item.name }}</div>
     }

     // Avoid: *ngFor
     <div *ngFor="let item of items">{{ item.name }}</div>
     ```
   - Follow smart/dumb component pattern
   - Use Angular's dependency injection
   - Implement proper lifecycle hooks

4. Project Specifics
   - Follow event-driven patterns
   - Maintain thread context
   - Keep tool implementations focused

## Git Guidelines

1. Branch Naming
   - Format: `type/username/description`
   - Types: feat, bugfix, other
   - Description: brief, hyphen-separated
   - Example: `feat/johndoe/add-file-tracking`

2. Development Process
   - No direct development on master
   - Create feature branch from master
   - Compile before commit
   - Clear commit messages

3. Commit Messages
   - Title: clear summary (max 50 chars)
   - Empty line after title
   - Description: wrapped at 72 chars
   - Include context and reasoning

## Testing Guidelines

> 🚧 Testing is currently being established in the project. These guidelines represent the target state and will evolve
> as we build up our testing infrastructure.

1. Current Testing Setup
   - Jest with TypeScript support recently added
   - First test implementations serving as examples
   - Files placed alongside their source (*.test.ts)
   - Basic configuration in jest.config.js

2. Test Structure (Current Pattern)
   ```typescript
   // Example from ai-thread.test.ts
   describe('ComponentName', () => {
     // Setup and helper functions
     beforeEach(() => {})

     describe('feature group', () => {
       it('should behave in specific way', () => {
         // Test implementation
       })
     })
   })
   ```

3. Available Commands
   ```bash
   # Run tests
   pnpm test

   # Development mode
   pnpm test:watch

   # With coverage
   pnpm test:coverage
   ```

4. Current Focus
   - Adding tests for new features
   - Using test coverage to identify gaps
   - Building testing patterns and examples
   - Learning from early implementations

5. Immediate Next Steps
   - Expand test coverage gradually
   - Refine testing patterns
   - Document learnings from implementations
   - Consider testing utilities needed

6. Future Considerations
   - Coverage targets to be defined
   - Integration testing strategy
   - Test data management
   - CI/CD integration
   - Mocking strategies

As testing matures in the project, these guidelines will be updated to reflect established patterns and best practices.

## Documentation Guidelines

1. Code Documentation
   - Document public interfaces
   - Explain non-obvious implementations
   - Document error conditions
   - Provide usage examples

2. Architectural Documentation
   - Keep high-level docs updated
   - Document significant decisions
   - Maintain view maps
   - Update domain model as needed

3. Configuration Documentation
   - Document setup procedures
   - Explain configuration options
   - Provide troubleshooting guides
   - Include example configurations

4. User Documentation
   - Clear command descriptions
   - Common usage patterns
   - Troubleshooting guide
   - Integration setup help
   - Best practices and examples

## AgentOS Guidelines

1. **Kotlin Code**
   - Immutability: `val` + data classes
   - Null safety: explicit `?` and safe calls
   - Coroutines for async operations

2. **Plugin Development**
   - Depend only on `agentos-sdk`
   - Use `@Extension` for plugin classes
   - YAML agents for rapid iteration, Kotlin for type safety

3. **Build**
   - Version Catalog: `gradle/libs.versions.toml`
   - Composite builds: SDK and Service independent
   - Nx for incremental CI builds

## Review Guidelines

1. Code Reviews
   - Verify architectural alignment
   - Check error handling
   - Validate testing coverage
   - Review documentation updates

2. Architecture Reviews
   - Evaluate against principles
   - Check scalability impact
   - Review security implications
   - Assess maintenance impact
