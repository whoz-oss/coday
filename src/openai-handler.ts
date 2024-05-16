import {CommandContext} from "./command-context";
import {CommandHandler} from "./command-handler";
import OpenAI from "openai";

const OPENAI_API_KEY = process.env['OPENAI_API_KEY'];

// TODO: remove this hard-coded test assistant
const assistantId: string = 'asst_eWqbqf5MnhItTYGqZ4zyRmH0'

export class OpenaiHandler extends CommandHandler {
    commandWord: string = 'ai'
    description: string = 'calls the AI with the given command and current context'

    openai: OpenAI | undefined
    threadId: string | null = null

    constructor() {
        super()
        this.openai = OPENAI_API_KEY ? new OpenAI({
            apiKey: process.env['OPENAI_API_KEY'],
        }) : undefined
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        if (!this.openai) {
            console.warn('OPENAI_API_KEY not set, skipping AI command')
            return context
        }

        const cmd = command.substring(this.commandWord.length).trim()

        if (!this.threadId) {
            const thread = (await this.openai.beta.threads.create())
            this.threadId = thread.id
        }

        // add the message from the user to the existing thread
        await this.openai.beta.threads.messages.create(this.threadId, {
            role: 'user',
            content: cmd
        })

        let result = ''
        const run = this.openai.beta.threads.runs.stream(this.threadId, {
            assistant_id: assistantId,
        }).on('textDone', (diff) => {
            result += diff.value;
        });
        await run.finalRun();
        console.log(`${result}`)
        return {...context, history: [...context.history, {command, response: result}]}
    }
}