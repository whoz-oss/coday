import {PromptChain} from "../model"

export const CodayPromptChains: (PromptChain & { name: string })[] = [
  {
    name: "small-task",
    description: "expand the request into a simple flow of requests : analysis, plan, review, execution, learn. No delegation to other assistants is encouraged nor sub-tasking.",
    commands: [
      `@ I want you to act as a coordinator (until asked otherwise) to manage the completion of the following request that you must **remember** in the whole thread.
      
## Request

PROMPT

## Process

You are the coordinator of the work done on the request. You direct, instruct, but do not execute.
You must keep a high-level position on the subject and make progress on the request by delegating the execution of the next step.
Each time you define a task and delegate it, the task is submitted to another async process that will take its time to complete the given task.

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

`
    ],
    requiredIntegrations: ["AI"]
  }, {
    name: "big-task",
    description: "Leverage small-task for deeper analysis and more granular execution.",
    commands: [
      `small-task analyse the following assignment in order to determine the scope and requirements of it.
      Pay attention to the keywords, mentions, and tone.
      Gather as much context as possible over the concerned topic.
      Identify the logical personas and the expectations they have and those they must satisfy
      
      The assignment: PROMPT`,
      `@ build a high-level plan to complete the assignment repeated here: PROMPT
      
      Focus on separating independent or clearly iterative steps.`,
      `sub-task true`,
      `@ from the plan previously built, craft high-level subtasks with explicit expectations, to submit through the subTask tool.
      Make sure to start the sub-task description with 'small-task' !!!`,
      `small-task review the completed work given the assignment repeated here: PROMPT
      
      Check the work has been done or at least seriously attempted.
      Summarize shortly what was done, what is missing if any and what could be the next steps.`,
      `sub-task false`
    ],
    requiredIntegrations: ["AI"]
  }, {
    name: "learn-about",
    description: "Pushes the AI to extract newly gained knowledge and memorize it.",
    commands: [
      `@ learn about PROMPT:
      
      From the recent context, formulate what you learned and that was not in the initially given context.
      Split it into cohesive subjects, formulate a short title (maximum one sentence) and a detailed description of what you learned that is new, specific to the user or the project, and useful to know.
      It should not be generic knowledge and neither a perfect copy on a file, but rather from a paragraph up to a 3 pages document.
      If unsure or lacking clarity, use the available tools to test or request guidance.
      Format it in markdown.
      Then memorize it, each subject at a time.`,
    ],
    requiredIntegrations: ["AI", "MEMORY"]
  }
]