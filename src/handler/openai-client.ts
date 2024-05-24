import {Interactor} from "../interactor";
import OpenAI from "openai";
import {OpenaiTools, Tool} from "./init-tools";
import {CommandContext} from "../command-context";
import {AssistantStream} from "openai/lib/AssistantStream";
import {Beta} from "openai/resources";
import Assistant = Beta.Assistant;

const OPENAI_API_KEY = process.env['OPENAI_API_KEY']
const ASSISTANT_INSTRUCTIONS = `
You are Coday, an AI assistant used interactively by users through a chat or alike interface.
By providing a sound and logic reasoning, you answer concisely to the user requests.
Among the provided functions, you shall use all that help you answer the request.
Unless explicitly asked for, keep your answers short.`

export class OpenaiClient {
    openai: OpenAI | undefined
    threadId: string | null = null
    assistantId: string | null = null
    textAccumulator: string = ""

    openaiTools: OpenaiTools

    constructor(private interactor: Interactor) {

        this.openaiTools = new OpenaiTools(interactor)
    }

    async isReady(): Promise<boolean> {
        if (!OPENAI_API_KEY) {
            this.interactor.warn('OPENAI_API_KEY env var not set, skipping AI command')
            return false
        }

        if (!this.openai) {
            this.openai = new OpenAI({
                apiKey: OPENAI_API_KEY,
            })
        }

        if (!this.assistantId) {
            // list assistants to find mine
            let after: string | undefined
            let mine: Assistant | undefined
            do {
                const fetchedAssistants: Assistant[] = (await this.openai.beta.assistants.list({
                    order: 'asc',
                    after,
                    limit: 100,
                }).withResponse()
                )
                    .data
                    .getPaginatedItems()
                mine = fetchedAssistants.find(a => a.name === `Coday_alpha`)
                after = fetchedAssistants.length > 0 ? fetchedAssistants[fetchedAssistants.length - 1].id : undefined;
            } while (after && !mine);


            if (!mine) {
                mine = await this.openai.beta.assistants.create({
                    name: `Coday_alpha`,
                    model: "gpt-4o",
                    instructions: ASSISTANT_INSTRUCTIONS,
                })
                this.interactor.displayText(`Created assistant ${mine.id}`)
            }

            this.assistantId = mine.id
        }

        if (!this.threadId) {
            const thread = (await this.openai.beta.threads.create())
            this.threadId = thread.id
        }

        return true
    }

    async answer(command: string, context: CommandContext): Promise<string> {
        this.textAccumulator = ""
        if (!await this.isReady()) {
            return "Openai client not ready"
        }

        const tools = this.openaiTools.getTools(context)

        // add the message from the user to the existing thread
        await this.openai!.beta.threads.messages.create(this.threadId!, {
            role: 'user',
            content: command
        })
        this.interactor.displayText("Asking Openai...")
        const assistantStream = this.openai!.beta.threads.runs.stream(this.threadId!, {
            assistant_id: this.assistantId!,
            tools,
            tool_choice: "auto"
        })

        await this.processStream(assistantStream, tools)

        await assistantStream.finalRun()

        return this.textAccumulator
    }

    async processStream(stream: AssistantStream, tools: Tool[]) {
        stream.on('textDone', (diff) => {
            this.interactor.displayText(diff.value, 'Coday')
            this.textAccumulator += diff.value
        })
        for await (const chunk of stream) {
            if (chunk.event === "thread.run.requires_action") {
                try {
                    const toolCalls = chunk.data.required_action?.submit_tool_outputs.tool_calls ?? []
                    const toolOutputs = await Promise.all(toolCalls.map(async (toolCall) => {
                        const funcWrapper = tools.find(t => t.function.name === toolCall.function.name)
                        if (!funcWrapper) {
                            throw new Error(`Function ${toolCall.function.name} not found.`)
                        }

                        const toolFunc = funcWrapper.function.function

                        // implicit assumption: have only a single object as input for all toolFunction?
                        let args: any = JSON.parse(toolCall.function.arguments)

                        // re-wrap a non-array argument for .apply()
                        if (!Array.isArray(args)) {
                            args = [args]
                        }
                        let output
                        try {
                            output = await toolFunc.apply(null, args)
                        } catch (err) {
                            this.interactor.error(err)
                            output = `Error on executing function, got error: ${JSON.stringify(err)}`
                        }

                        // Ensure output is at least something
                        if (!output) {
                            output = `Tool function ${funcWrapper.function.name} did not return a value.`
                            this.interactor.error(output)
                        }

                        if (typeof output !== 'string') {
                            output = JSON.stringify(output)
                        }

                        return {tool_call_id: toolCall.id, output}
                    }))

                    const newStream = this.openai!.beta.threads.runs.submitToolOutputsStream(
                        this.threadId!,
                        chunk.data.id,
                        {tool_outputs: toolOutputs}
                    )

                    // Recursively process the newly returned stream
                    await this.processStream.call(this, newStream, tools)
                } catch (error) {
                    console.error(`Error processing tool call`, error)
                }
            }
        }
    }
}