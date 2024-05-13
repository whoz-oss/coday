import OpenAI from "openai";
import {CommandContext} from "./command-context";

const openaiApiKey = process.env['OPENAI_API_KEY']
const openaiCodayAssistantId = process.env['CODAY_ASSISTANT_ID'] ?? ""

if (!openaiApiKey) {
    console.warn('Missing OPENAI_API_KEY, no ChatGPT call will work')
}
// if (!openaiCodayAssistantId) {
//     console.warn('Missing CODAY_ASSISTANT_ID, no ChatGPT call will work')
// }

export class OpenaiFunction {
    openai = new OpenAI({
        apiKey: openaiApiKey
    })
    async ask(prompt: string, context: CommandContext): Promise<{ answer: string; context: CommandContext; }> {
        let thread: OpenAI.Beta.Threads.Thread
        // get thread or create if any
        if (context?.openai?.threadId) {
            // retrieve existing thread
            thread = await this.openai.beta.threads.retrieve(context.openai.threadId)
        } else {
            // create a new one
            thread = await this.openai.beta.threads.create()
        }

        //
        let run = this.openai.beta.threads.runs.create(
            thread.id,
            {
                assistant_id: openaiCodayAssistantId,
                model: "gpt-4-turbo-preview",
                instructions: prompt,
                tools: [] // TODO: add functions here
            }
        )

        return {
            answer: "",
            context: {...context, openai: { threadId: thread.id}}
        }
    }
}