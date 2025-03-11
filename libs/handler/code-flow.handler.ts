import { CommandContext, CommandHandler } from '../model'

export class CodeFlowHandler extends CommandHandler {
  constructor() {
    super({
      commandWord: 'code',
      description: 'expand the request into a flow of requests : analysis, plan, sub-tasking, implementation, review',
    })
  }

  async handle(command: string, context: CommandContext): Promise<CommandContext> {
    const cmd = this.getSubCommand(command)

    const expertInstructions = [
      // PREPARATION
      // gather data
      `@ You are given this assignment: ${cmd}
            
            As a first step, analyse the assignment, search for keywords, files or external references using available functions, search also for validation or review material, being documentation or existing tests.
            If other relevant assistants available to do their research and complement your findings, or delegate entirely this research to them.
            Do not start working on the solution (either you or other assistant), just gather data to get a more detailed and deeper understanding of the assignment.`,

      // other assistants may intervene here, let them "speak"...
      // ... then collect their findings
      `@ Give an expanded summary of all the previous findings, explaining the assignment. Do not start working on the solution.`,
      `add-query Do you have anything to add or correct about this analyse ?`,
      `@ Prepare the workspace by following project rules on workflow (example: create the git branch dedicated to the assignment)`,

      // EXECUTION
      // build a plan
      `@ Build one or more rough plans of actions on how to complete the assignment.
            Then evaluate quickly the pros and cons of each.`,
      `add-query Do you have anything to add or correct about these plans ?`,
      `@ Finally, choose the best option in regard of the project rules, and review how implementing it would complete the assignment`,

      // execute the plan
      `sub-task 3`, // arbitrary sub-task token count to cover at least the initial sub-tasking
      `@ Expand on the chosen plan by detailing a sequential plan of all the tasks to implement (no less than 2, no more than 10).
             
            Each task should:
             - be as independent and atomic as possible and contain a clear definition of what is to be done.
             - incorporate already known details such as files, references or key words
             - be fully delegated to a known relevant assistant by pre-fixing its description with the assistant name
             - include the expectations and ways or functions to use to validate its execution depending on the project rules
             - be commited or saved once completed and validated, by following the project rules
             
             DO NOT COMPLETE THESE TASKS NOW, but send them to the delegate function (for later sequential execution).`,

      // ...sub-tasks are run and burn a hole in the token quota of the month...
      `sub-task false`, // removal to prevent any later over-complexity

      // review
      `@ Review the plan completion regarding the given assignment and make sure work done satisfies the quality constraints of the project.`,

      // CLOSURE
      `@ Prepare a short presentation of the work done.`,
      `add-query How do you want to conclude this flow ?`,
      // `If available, publish it for manual review and validation.`
    ]

    context.addCommands(...expertInstructions)
    return context
  }
}
