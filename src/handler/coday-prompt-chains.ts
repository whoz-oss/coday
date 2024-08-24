import {IntegrationName, PromptChain} from "../model"

export const CodayPromptChains: (PromptChain & { name: string })[] = [
  {
    name: "small-task",
    description: "expand the request into a simple flow of requests : analysis, plan, review, execution, learn. No delegation to other assistants is encouraged nor sub-tasking.",
    commands: [
      `@ I want you to complete the following assignment in a structured way : PROMPT
      
As a first step, analyse the task, search for keywords, files or external references using available functions, search also for validation or review material, being documentation or existing tests.
Do not start working on the solution, just gather data to get a more detailed and deeper understanding of the task.`,
      `@ Now build a sound and reasonable plan on how to complete the task. Don't get carried over, keep things simple and respect the spirit or flavor of the project.
DO NOT EXECUTE THE PLAN NOW !`,
      `@ Then review your plan, search for improvements and weaknesses to cover, still in the spirit of the project.
DO NOT EXECUTE THE PLAN NOW !`,
      `@ Execute the built plan, taking into account any previous message, and all project rules.
            Make sure to adjust the execution to any hiccups and still try to complete the task.`,
      `learn-about`
    ],
    requiredIntegrations: [IntegrationName.OPENAI]
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
    requiredIntegrations: [IntegrationName.OPENAI]
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
    requiredIntegrations: [IntegrationName.OPENAI, IntegrationName.LOCAL_MEMORY]
  }
]