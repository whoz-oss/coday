# Development Guidelines

## Architectural Principles

[Previous architectural principles section]

## Code Guidelines

1. Core Principles
   - Keep it simple over clever
   - Document public interfaces
   - Handle errors explicitly
   - Use TypeScript features appropriately

2. Project Specifics
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
   yarn test
   
   # Development mode
   yarn test:watch
   
   # With coverage
   yarn test:coverage
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