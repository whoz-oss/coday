# Plan Mode

Plan Mode is a feature inspired by Cursor's plan mode that allows Coday to create detailed execution plans before tackling complex tasks, then execute those plans step-by-step.

## Overview

Plan Mode consists of two complementary prompt chains:
1. **`plan`** - Creates a detailed, structured execution plan
2. **`execute-plan`** - Executes a previously created (and potentially user-edited) plan

This approach separates planning from execution, allowing users to review and modify plans before implementation.

## Usage

### Creating a Plan

```bash
user> plan Add authentication system with JWT tokens
```

The agent will:
1. **Research & Understanding**: Analyze requirements, research context using available tools
2. **Clarifying Questions**: Ask focused questions if needed (using invite/choice tools)
3. **Plan Creation**: Generate a detailed plan and save it to `exchange://execution-plan.md`

### Reviewing the Plan

```bash
user> load exchange://execution-plan.md
```

You can manually edit the file or ask the agent to modify it:

```bash
user> @ modify the plan to use refresh tokens as well
```

### Executing the Plan

```bash
user> execute-plan
```

The agent will:
1. Load the plan from `exchange://execution-plan.md` (respecting any edits)
2. Parse the plan structure
3. Execute each step in order with validation
4. Report progress and issues
5. Provide a final summary

## Plan Format

Plans follow a structured Markdown format:

```markdown
# Execution Plan: [Task Title]

## Overview
Brief 1-2 sentence summary.

## Context
- Key information and constraints
- Relevant patterns/conventions
- Dependencies or prerequisites

## Steps

### Step 1: [Step Title]
- **Resources to modify**: Files, APIs, resources
- **Changes**: Specific modifications
- **Validation**: How to verify (compile, test, manual)
- **Dependencies**: Previous steps required (or "None")

### Step 2: [Step Title]
[Same structure...]

## Verification
1. Compilation checks
2. Automated tests
3. Manual verification steps

## Risks & Considerations
- Potential issues or edge cases
- Alternative approaches
- Technical debt or future improvements
```

## Benefits

1. **Separation of Concerns**: Planning separate from execution
2. **User Control**: Plans can be reviewed and edited before execution
3. **Traceability**: Plans saved in `exchange://` for audit and reference
4. **Flexibility**: Works with any agent toolset (API-based or file-based)
5. **Iterative Improvement**: Plans can be refined based on feedback

## Example Workflow

```bash
# 1. Create a plan
user> plan Implement user authentication with JWT

[Agent researches, asks questions, creates plan]
Agent: "Plan created and saved to exchange://execution-plan.md.
       Review it with 'load exchange://execution-plan.md'
       or execute directly with 'execute-plan'."

# 2. Review (optional)
user> load exchange://execution-plan.md
[Plan displayed]

# 3. Edit if needed (optional)
user> @ add rate limiting to the authentication endpoints

# 4. Execute
user> execute-plan

[Agent executes step by step with validation]
[Final summary provided]
```

## Tips

- **Complex Tasks**: Use Plan Mode for multi-step tasks with dependencies
- **Review Before Execute**: Always review the plan before execution for complex changes
- **Iterative Refinement**: Don't hesitate to ask the agent to modify the plan
- **Tool Agnostic**: The prompts work with any agent toolset (file operations, API calls, etc.)
- **Progress Tracking**: Execution provides clear feedback at each step

## Comparison with Direct Execution

| Aspect | Direct Execution | Plan Mode |
|--------|------------------|-----------|
| **Approach** | Immediate implementation | Plan first, execute later |
| **Visibility** | Limited upfront visibility | Full plan visible before execution |
| **Control** | Limited control over approach | Can review and edit plan |
| **Complexity** | Best for simple tasks | Best for complex multi-step tasks |
| **Traceability** | Limited documentation | Plan saved in exchange:// |

## Implementation Details

- **Location**: `libs/handler/coday-prompt-chains.ts`
- **Plan Storage**: `exchange://execution-plan.md`
- **Dependencies**: None (uses existing prompt chain infrastructure)
