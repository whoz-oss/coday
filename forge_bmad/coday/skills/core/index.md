# Core Skills — Index

Cross-cutting skills for every agent that writes code or orchestrates work. Loaded as mandatory by executing agents (Frontend, Backend, orchestrators, code-writing specialists) alongside their domain index.

**Rule:** Load the specific skill when its trigger matches — do not execute these workflows from memory.

| Skill                        | File                         | Load when...                                                                                                            |
|------------------------------|------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| Branch Creation              | `branch-creation/SKILL.md`         | Starting work on a ticket — source branch deduced from ticket type, name pattern `<type>/<username>/<ticket-id>_<desc>` |
| Commit Message Format        | `git-commit/SKILL.md`              | Creating a commit or drafting a message — ticket ID deduced from branch name, summarized style                          |
| PR Creation & Description    | `pr-description/SKILL.md`          | Creating a pull request — title, base branch deduction, template                                                        |
| Environment & Branch Routing | `env-troubleshooting/SKILL.md`     | Bug report mentions an env, or deciding which branch a fix lands on                                                     |
| Writing Delegation Tasks     | `delegation-task-writing/SKILL.md` | Delegating work to another agent — self-contained task checklist                                                        |
| Agent Config Editing         | `agent-config-editing/SKILL.md`    | Modifying agent YAMLs in `coday/agents/` — mirror rule, conventions, design principles                                  |
| Memory Hygiene               | `memory-hygiene/SKILL.md`          | About to memorize, or reviewing/cleaning memories                                                                       |
| Factory Workflow Projection  | `factory-workflow-projection/SKILL.md` | Running `/run-factory`, selecting an owned domain workflow declaration, or publishing through `FACTORY__publish_projection` |
| Git Staging                  | `git-staging/SKILL.md`             | After creating a new file or directory — stage it immediately with `git add`                                            |
| Release Doc Update           | `release-doc-update/SKILL.md`      | Adding or modifying a migration script under `cli/lib/migration/`                                                       |
| Migration New Version        | `migration-new-version/SKILL.md`   | Creating a migration script for a version that has no folder yet under `cli/lib/migration/<version>/`                   |
