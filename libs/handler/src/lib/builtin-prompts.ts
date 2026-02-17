import { Prompt } from '@coday/model'

/**
 * Built-in system prompts
 *
 * These prompts are available out-of-the-box as slash commands.
 * They are read-only and not managed through PromptService CRUD operations.
 *
 * Built-in prompts are only visible when executing slash commands,
 * not in configuration/management interfaces.
 */
export const BuiltinPrompts: Prompt[] = [
  {
    id: 'builtin-plan',
    name: 'plan',
    description: 'Build a plan to complete the given task',
    source: 'builtin',
    commands: [
      `@ We start a 3 phase process to build a comprehensive execution plan for the following task:

<task>
{{PARAMETERS}}
</task>

There will be 3 phases:
1. Research and understanding
2. Clarifying questions
3. Plan creation

Do not try to do all at once, wait for my request at each time.

Now we start with phase 1:

1. **Analyze the request** to identify:
   - Core requirements and objectives
   - Implicit assumptions or expectations
   - Technical scope and constraints

2. **Research relevant context** using available tools:
   - Use whatever tools you have available (file reading, API calls, search, etc.)
   - Gather information about existing patterns, structures, and conventions
   - Identify relevant files, modules, or resources
   - Document key findings briefly

3. **Assess feasibility**:
   - Identify potential challenges or blockers
   - Note any dependencies or prerequisites
   - Consider alternative approaches if applicable

Do not assume you have specific tools - use what's available to you.

Do not produce any document at this stage as you will have access to the accumulated research results later.
      `,
      `@ let's continue with Phase 2: Clarifying Questions regarding the initial task.
      
<task>
{{PARAMETERS}}
</task>

Based on your research and understanding:

1. **Identify ambiguities** or missing information that could impact the completion of the task
   - no boundaries
   - insufficient definition of done
   - lack of pre-requisites

2. **Ask focused questions** if clarification is needed:
   - Use the 'invite' or 'choice' tools for user input
   - Keep questions concise and specific
   - Prioritize questions that significantly impact the approach

3. **Skip this phase** if the requirements are clear enough to proceed

Remember: Only ask questions that are truly necessary to create a solid plan.`,
      `@ finish with Phase 3: Plan Creation

Create a detailed execution plan:
- if you have access to the exchange area, save the plan there as 'plan.md'.
- otherwise, output the plan in the conversation.
- do not write any other document

The plan must follow this Markdown structure:

# Execution Plan: [Task Title]

## Overview
1-2 sentence summary of what will be accomplished.

## Context
Key information and constraints identified during research:
- Relevant patterns/conventions found
- Technical constraints
- Dependencies or prerequisites
- Key files/resources involved

## Steps

### Step 1: [Step Title]
- **Resources to modify**: List files, APIs, or resources
- **Changes**: Specific modifications to make
- **Validation**: How to verify this step (compile, test, manual check)
- **Dependencies**: Which previous steps must complete first (or "None")

### Step 2: [Step Title]
[Same structure as Step 1]

[Continue for all steps...]

## Verification
How to test the complete implementation:
1. Compilation checks
2. Automated tests
3. Manual verification steps

## Risks & Considerations
- Potential issues or edge cases
- Alternative approaches considered
- Technical debt or future improvements

---

After saving the plan:
1. Inform the user that the plan is ready
2. Mention it can be reviewed with: load exchange://execution-plan.md
3. Mention it can be edited manually or via agent assistance
4. Mention it can be executed with: execute-plan`,
    ],
    createdBy: 'system',
    createdAt: new Date('2024-01-01').toISOString(),
    webhookEnabled: false,
    parameterFormat: '',
  },
  {
    id: 'builtin-re-prompt',
    name: 're-prompt',
    description: 'Transform a vague request into a detailed, actionable prompt',
    source: 'builtin',
    commands: [
      `@ I need you to analyze and reformulate the following request to make it more precise and actionable.

## User's initial request

{{PARAMETERS}}

## Your task

1. **Analyze the request** to identify:
   - What is explicitly stated
   - What is implicit or assumed
   - What information is missing or ambiguous
   - The likely intent behind the request

2. **Ask clarifying questions** if needed:
   - If the request is too vague or has multiple interpretations
   - If critical information is missing
   - Use the 'invite' or 'choice' tools to gather necessary details

3. **Reformulate the request** into a clear, detailed, and actionable prompt:
   - Make implicit requirements explicit
   - Add relevant context from the current project
   - Specify expected outcomes or deliverables
   - Break down complex requests into clear steps if needed
   - Use precise technical terminology when appropriate

4. **Present the reformulated request** and ask for confirmation:
   - Show the enhanced version clearly
   - Explain key changes or additions you made
   - Ask if this matches the user's intent

The goal is to transform a rough idea into a well-defined request that will produce better AI responses.`,
    ],
    createdBy: 'system',
    createdAt: new Date('2024-01-01').toISOString(),
    webhookEnabled: false,
    parameterFormat: '',
  },
]
