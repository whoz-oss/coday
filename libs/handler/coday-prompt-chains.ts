import { PromptChain } from '../model'

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
]
