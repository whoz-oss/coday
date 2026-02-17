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

Break down the task into 3-10 clear, actionable steps. Each step should be substantial enough to represent meaningful progress, but focused enough to be completed in one session.

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
- Constraints or limitations
- Dependencies or prerequisites
- Key resources or materials involved

## Steps

### Step 1: [Step Title]
- **What to do**: Clear description of the action to take
- **Resources needed**: List files, tools, information, or materials required
- **Expected outcome**: What should be produced or achieved
- **Validation**: How to verify this step is complete
- **Dependencies**: Which previous steps must complete first (or "None")

### Step 2: [Step Title]
[Same structure as Step 1]

[Continue for all steps...]

## Success Criteria
How to verify the task is fully complete:
1. Objective measures of completion
2. Quality checks or review points
3. Final validation steps

## Risks & Considerations
- Potential challenges or obstacles
- Alternative approaches considered
- Areas requiring special attention
- Future improvements or follow-up items

---

After outputting the plan in the right place (in exchange or in conversation or according to user directives):
- do not produce other outputs nor documents
- do not start executing the plan`,
    ],
    createdBy: 'system',
    createdAt: new Date('2024-01-01').toISOString(),
    webhookEnabled: false,
    parameterFormat: '',
  },
  {
    id: 'builtin-execute-plan',
    name: 'execute-plan',
    description: 'Execute a previously created plan step by step, with validation and progress tracking.',
    source: 'builtin',
    commands: [
      `@ Execute the plan from the exchange area or outlined previously in the conversation:

## Preparation
1. **Load the plan**: Read from exchange area (plan.md) or use previously discussed plan
2. **Review**: Understand the overall objective, context, and step sequence
3. **Pre-check**: Verify prerequisites are met; report any obvious issues

## Step-by-Step Execution

For each step in order:

1. **Announce**: State which step you're starting and what you'll do
2. **Check dependencies**: Verify required previous steps are complete
3. **Execute**: Perform the actions specified in the step
4. **Validate**: Apply the validation criteria from the plan
5. **Report**: Confirm completion and note any deviations

**For significant or risky steps**: Ask user confirmation before proceeding (use 'choice' tool)

## Issue Handling

If a step fails:
- Report the issue clearly
- Suggest solutions (retry, skip if non-critical, or stop)
- Use 'choice' tool to ask how to proceed if needed

## Completion

1. **Final verification**: Apply success criteria from the plan
2. **Summary**: Report what was accomplished, any deviations, validation results, and next steps

## Guidelines
- Follow the plan's order unless circumstances require adaptation
- Communicate progress clearly at each step, without excessive details
- Ask for guidance when the plan is unclear or issues arise`,
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
  {
    id: 'builtin-memory-learn',
    name: 'memory-learn',
    description: 'Extract and memorize new knowledge gained from recent context',
    source: 'builtin',
    commands: [
      `@ Learn about the following topic and memorize relevant insights if defined:

<topic>
{{PARAMETERS}}
</topic>

If no specific topic is provided, extract learnings from the entire recent conversation context.

## Process

1. **Identify new knowledge**: From the recent conversation context, identify what you learned that:
   - Was not in the initially given context
   - Is specific to this user or project (not generic knowledge)
   - Is useful and worth remembering
   - Represents understanding, not just file copies

2. **Organize insights**: Split the knowledge into cohesive subjects, each with:
   - **Title**: Short and descriptive (one sentence maximum)
   - **Content**: Detailed explanation of what was learned
     - Length: at most 3 paragraphs or short extracts and notes
     - Format: Well-structured markdown
     - Focus: Specific patterns, decisions, constraints, or insights

3. **Check for redundancy**: Before memorizing:
   - Review the list of existing memories to avoid duplication
   - If a similar memory exists, consider updating it instead of creating a new one
   - delete memories if needed

4. **Memorize**: Use the memorization tools to save each subject:
   - Choose appropriate level if available (PROJECT or USER)
   - Ensure clear structure and completeness
   - Memorize one subject at a time

## Guidelines
- Focus on actionable insights, not generic facts
- Ensure memories are self-contained and clear
- Avoid redundancy with existing memories
- Prioritize quality over quantity`,
    ],
    createdBy: 'system',
    createdAt: new Date('2024-01-01').toISOString(),
    webhookEnabled: false,
    parameterFormat: '',
  },
]
