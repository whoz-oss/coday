# Development Workflow

This document outlines the technical workflow steps and rules for contributing to the Coday project.

## Branch Naming

Always follow these branch naming conventions:
```
fix/username/issue-XXXX-short-description    # For bug fixes
feature/username/issue-XXXX-feature-name     # For new features
refactor/username/issue-XXXX-description     # For code refactoring
docs/username/issue-XXXX-description         # For documentation updates
chore/username/issue-XXXX-description        # For maintenance tasks
build/username/issue-XXXX-description        # For build system changes
```

## Commit Messages

Use conventional commit format:
```
fix: #XXXX short description       # For bug fixes
feat: #XXXX short description      # For features
refactor: #XXXX short description  # For refactoring
docs: #XXXX short description      # For documentation
test: #XXXX short description      # For test additions/changes
chore: #XXXX short description     # For maintenance tasks
build: #XXXX short description     # For build system changes
```

## Bug Fix Workflow

1. Create branch using the naming convention, from the remote `master` branch
2. Implement the fix
3. Test the fix:
   ```bash
   # Compile the entire project
   yarn nx run-many --target=build --all
   
   # Verify fix works in both web and terminal interfaces
   yarn start    # Test terminal interface
   yarn web      # Test web interface
   ```
4. Commit and push
5. Create PR with conventional commit format as title (e.g., "fix: #XXXX short description")

## Feature Development Workflow

1. Create branch using the naming convention, from the remote `master` branch
2. Consider architectural impact before implementation
3. Follow SOLID principles and existing patterns. KISS: Keep It Stupid Simple
4. Implement the feature
5. Verify:
   ```bash
   # Compile the entire project
   yarn nx run-many --target=build --all
   
   # Add automated tests if appropriate
   yarn test
   ```
6. Commit and push
7. Create PR with conventional commit format as title (e.g., "feat: #XXXX short description")

## Pull Request Requirements

1. PR title must follow conventional commit format
2. All commits in a PR must be squashed when merging (squash and merge is mandatory)
3. Address all review comments before merging
4. Wait for CI checks to pass before merging

## Important Commands

```bash
# Start the terminal interface
yarn start

# Start the web interface
yarn web

# Run tests
yarn test

# Lint code
yarn lint

# Debug web interface
yarn web:debug
```