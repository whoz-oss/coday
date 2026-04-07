# Code Style

## Kotlin Conventions

- **Prefer `when` over chained `if`/`else if`** — even for simple two-branch conditions, `when` is more readable and scales better as cases are added.
- **Avoid early returns in the middle of a method** — structure the logic so the single return is at the end (or use expression bodies). Early returns increase cyclomatic complexity and make control flow harder to follow.
