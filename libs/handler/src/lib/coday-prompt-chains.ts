import { PromptChain } from '@coday/model'

export const CodayPromptChains: (PromptChain & { name: string })[] = [
  {
    name: 'memory-curate',
    description: 'Curate the memory system by analyzing redundancies, outdated information, and ensuring consistency.',
    commands: [
      `@ First, analyze the current memories for potential redundancies:

1. Check memories that:
   - Cover similar topics
   - Have overlapping information
   - Could be consolidated for clarity

2. For each group of redundant memories:
   - Delete individual memories using deleteMemory
   - Create new consolidated memory that:
     - Maintains all critical details
     - Provides clear structure
     - Uses appropriate level (PROJECT/USER)

Be proactive but careful in this consolidation, explaining your reasoning.`,

      `@ Now, analyze remaining memories for outdated or incorrect information:

1. Compare each memory against:
   - Other memories for inconsistencies
   - Project context for outdated info
   - Core documentation for misalignments

2. For each problematic memory:
   - List the title with short explanation of the issue
   - Take appropriate action:
     - Delete if outdated/incorrect
     - Update if partial/incomplete
     - Consolidate if better fits elsewhere

Be thorough in your analysis and clear in your explanations.`,

      `@ Finally, verify the memory system's current state:

1. Review the overall structure
2. Check each memory for:
   - Completeness and validity
   - Proper categorization (PROJECT/USER)
   - Clear structure and organization
3. Document any remaining issues or recommendations

This ensures the memory system stays efficient and valuable.`,
    ],
  },
  {
    name: 'small-task',
    description:
      'expand the request into a simple flow of requests : analysis, plan, review, execution, learn. No delegation to other assistants is encouraged nor sub-tasking.',
    commands: [
      `@ I want you to act as a coordinator (until asked otherwise) to manage the completion of the following request that you must **remember** in the whole thread.

## Request

PROMPT

## Process

You are the coordinator of the work done on the request. You direct, instruct, but do not execute.
You must keep a high-level position on the subject and make progress on the request by delegating the execution of the next step.
Each time you define a task and delegate it, the task is submitted to another async process that will take its time to complete the given task.

When delegated task is completed, you'll receive a summary of the work done.

In another message, I'll bring you the synthetic result of the delegated task, at which moment you'll be able to define the next task to make progress on the request.
This is an iterative process, so unconclusive delegated tasks can be retried, adjusted, or dropped in their approach to attempt something else.

## First steps

Before starting this iterative process:

- reformulate the request to make obvious the implicit requirements
- conduct a quick analysis of the subject
- internally draft a plan and review it

## First delegated task

Given the analysis and the plan, define the first task and delegate it.`,
      // Here the process should iterate on the task
      `Here is the initial request you were given:

## Initial Request

PROMPT

## Final report

You deemed the task completed by not delegating another task, so:

- give a status of completion
- summarize the work done
- list observations collected in the process, being issues encountered, potential follow-ups or next steps (do not try to fill absolutely this section though)

`,
    ],
  },
  {
    name: 'big-task',
    description: 'Leverage small-task for deeper analysis and more granular execution.',
    commands: [
      `small-task analyse the following assignment in order to determine the scope and requirements of it.
      Pay attention to the keywords, mentions, and tone.
      Gather as much context as possible over the concerned topic.
      Identify the logical personas and the expectations they have and those they must satisfy

      The assignment: PROMPT`,
      `@ build a high-level plan to complete the assignment repeated here: PROMPT

      Focus on separating independent or clearly iterative steps.`,
      `@ from the plan previously built, craft high-level subtasks with explicit expectations, to submit through the 'delegate' tool.
      Make sure to start the delegated task description with 'small-task' !!!`,
      `small-task review the completed work given the assignment repeated here: PROMPT

      Check the work has been done or at least seriously attempted.
      Summarize shortly what was done, what is missing if any and what could be the next steps.`,
    ],
  },
  {
    name: 'learn-about',
    description: 'Pushes the AI to extract newly gained knowledge and memorize it.',
    commands: [
      `@ learn about PROMPT:

      From the recent context, formulate what you learned and that was not in the initially given context.
      Split it into cohesive subjects, formulate a short title (maximum one sentence) and a detailed description of what you learned that is new, specific to the user or the project, and useful to know.
      It should not be generic knowledge and neither a perfect copy on a file, but rather from a paragraph up to a 3 pages document.
      If unsure or lacking clarity, use the available tools to test or request guidance.
      Format it in markdown.
      Then memorize it, each subject at a time.`,
    ],
  },
  {
    name: 'plan',
    description:
      'Create a detailed execution plan before starting complex tasks. Research context, ask clarifying questions, and generate structured plan.',
    commands: [
      `@ Create a comprehensive execution plan for the following task:

PROMPT

## Phase 1: Research & Understanding

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

Do not assume you have specific tools - use what's available to you.`,

      `@ Continue with Phase 2: Clarifying Questions

Based on your research and understanding:

1. **Identify ambiguities** or missing information that could impact the plan

2. **Ask focused questions** if clarification is needed:
   - Use the 'invite' or 'choice' tools for user input
   - Keep questions concise and specific
   - Prioritize questions that significantly impact the approach

3. **Skip this phase** if the requirements are clear enough to proceed

Remember: Only ask questions that are truly necessary for creating a solid plan.`,

      `@ Complete Phase 3: Plan Creation

Now create a detailed execution plan and save it to exchange://execution-plan.md

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
  },
  {
    name: 'execute-plan',
    description: 'Execute a previously created plan step by step, with validation and progress tracking.',
    commands: [
      `@ Execute the plan from exchange://execution-plan.md

## Important Notes
- The plan file may have been edited by the user since creation
- Always read the current version from exchange://execution-plan.md
- Respect the plan structure and order unless issues arise

## Execution Protocol

### 1. Load and Parse the Plan
- Read the plan from exchange://execution-plan.md
- Parse the structure to understand:
  - Overall objective
  - Context and constraints
  - Ordered steps with dependencies
  - Validation criteria

### 2. Pre-execution Check
- Review the plan overview and context
- Verify prerequisites are met
- Identify any obvious issues with the plan
- If significant issues found, report them and ask for guidance

### 3. Step-by-Step Execution
For each step in order:

a) **Announce the step**:
   - State which step you're starting
   - Briefly explain what will be done

b) **Check dependencies**:
   - Verify required previous steps completed successfully
   - If dependencies not met, report and stop

c) **Execute the changes**:
   - Make the specified modifications
   - Follow the plan's guidance
   - If plan lacks details, use best judgment based on context

d) **Validate the step**:
   - Run specified validation (compile, test, etc.)
   - Verify changes work as expected
   - Report any issues immediately

e) **Optional confirmation** (for complex/risky steps):
   - For steps that modify critical files or have significant impact
   - Ask user confirmation before proceeding to next step
   - Use 'choice' tool for quick yes/no

f) **Report completion**:
   - Confirm step completed successfully
   - Note any deviations from the plan
   - Proceed to next step

### 4. Handle Issues
If a step fails:
- Report the specific issue clearly
- Explain what went wrong
- Suggest potential solutions:
  - Retry with adjustments
  - Skip and continue (if non-critical)
  - Stop and ask for guidance
- Use 'choice' tool to ask user how to proceed

### 5. Final Verification
After all steps complete:
- Run final validation checks from the plan
- Test the complete implementation
- Verify all objectives met

### 6. Summary Report
Provide a comprehensive summary:
- What was accomplished
- List of changes made (files modified, features added, etc.)
- Any deviations from the original plan and why
- Validation results (tests passed, compilation successful, etc.)
- Known issues or limitations
- Suggested next steps or follow-ups

## Execution Guidelines
- Be methodical and thorough
- Validate frequently to catch issues early
- Communicate progress clearly
- Don't hesitate to ask for guidance if plan is unclear
- Respect the plan but adapt if circumstances require it`,
    ],
  },
]
