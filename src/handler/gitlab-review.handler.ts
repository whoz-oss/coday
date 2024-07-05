import { CommandHandler } from "./command-handler"
import { CommandContext } from "../command-context"


export class GitlabReviewHandler extends CommandHandler {
    commandWord: string = 'review'
    description: string = 'takes the ID of the merge request to perform a comprehensive review.'

    constructor() {
        super()
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd = this.getSubCommand(command)

        const reviewInstructions = [
            `@ You are given this merge request identifier to review and if present, other directives for the review: ${cmd}
            Provide a clear and concise analysis of the changes introduced by this merge request.
            Summarize the impact these changes will have on existing features.
            Do not add comments yet.`,

            `add-message Would you like to add or correct anything regarding the impact on existing features?`,

            `@ Identify any potentially problematic or suspicious code within the changes.
            List specific changes that could be enhanced, and suggest new source code where applicable.
            Evaluate the relevance of names used for files, classes, methods, or fields, and propose better alternatives if needed.
            Do not add comments yet.`,

            `add-message What would you like to add or change regarding this review?`,

            `@ Apply your feedback to the merge request:
            - add a global comment to provide overarching feedback that applies to the entire MR.
            - add an MR thread to give detailed feedback on specific lines of code. If the exact line is not available, default to line 1.
            - Indicate the severity of each issue using emojis to convey the level of urgency or importance.
            - If you're unable to attach feedback to a specific file, ensure it's included in the global feedback.`,

        ]

        context.addCommands(...reviewInstructions)
        return context
    }
}
