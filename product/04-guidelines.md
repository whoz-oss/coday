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

1. Current Approach
   - Focus on critical paths
   - Test main user flows
   - Basic error cases

2. Future Evolution
   - Build test coverage progressively
   - Identify key test areas
   - Plan for integration tests

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