import {CommandContext} from "./command-context";
import {CommandHandler} from "./command-handler";
import OpenAI from "openai";

const OPENAI_API_KEY = process.env['OPENAI_API_KEY'];

export class OpenaiHandler extends CommandHandler {
    commandWord: string = 'ai'
    description: string = 'calls the AI with the given command and current context'

    openai: OpenAI

    constructor() {
        super()
        if (!OPENAI_API_KEY) throw new Error('OPENAI_API_KEY is not set')
        this.openai = new OpenAI({
            apiKey: process.env['OPENAI_API_KEY'], // This is the default and can be omitted
        });

    }

    handle(command: string, context: CommandContext): Promise<CommandContext> {
        const cmd = command.substring(this.commandWord.length).trim()
        return new Promise<CommandContext>((resolve, reject) => {

            let result = ''
            console.log('Calling OpenAI...')
            const runner = this.openai.beta.chat.completions.stream({
                messages: [
                    {
                        role: 'system',
                        content: `You are an AI assistant here to help the user in the given context:
${context}
                        `
                    },
                    {
                        role: 'user',
                        content: cmd
                    }
                ],
                stream: true,
                model: 'gpt-4-1106-preview',
            }).on('content', (diff) => {
                    result += diff;
                });

            const chatCompletion = runner.finalChatCompletion()
            chatCompletion.then(_ => {
                console.log(`${result}`);
                const updatedContext = { ...context, history: [...context.history, { command, response: result }] }
                resolve(updatedContext)
            }).catch(reason => {
                reject(reason)
            })
        })

    }
}