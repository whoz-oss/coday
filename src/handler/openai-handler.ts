import {CommandContext} from "../command-context"
import {CommandHandler} from "./command-handler"
import OpenAI from "openai"
import {Interactor} from "../interactor"
import {AssistantStream} from "openai/lib/AssistantStream"
import {OpenaiTools, Tool} from "./init-tools";

const OPENAI_API_KEY = process.env['OPENAI_API_KEY']

export class OpenaiHandler extends CommandHandler {
    commandWord: string = 'ai'
    description: string = 'calls the AI with the given command and current context'

    openai: OpenAI | undefined
    threadId: string | null = null
    assistantId: string | null = null
    textAccumulator: string = ""

    openaiTools: OpenaiTools

    constructor(private interactor: Interactor) {
        super()
        this.openai = OPENAI_API_KEY ? new OpenAI({
            apiKey: process.env['OPENAI_API_KEY'],
        }) : undefined
        this.openaiTools = new OpenaiTools(interactor)
    }


    async initOpenai(): Promise<boolean> {
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
            const assistants = (await this.openai.beta.assistants.list({
                order: 'asc',
                limit: 100
            }).withResponse()).data.getPaginatedItems()
            let mine = assistants.find(a => a.name === `Coday_alpha`)

            if (!mine) {
                mine = await this.openai.beta.assistants.create({
                    name: `Coday_alpha`,
                    model: "gpt-4o",
                    instructions: "You are an assistant for Whoz, a company developing a staffing SAAS solution for large companies. As such, you are knowledgeable in staffing management, in product design, and in the used software stack (frontend in Angular & typescript, backend in Spring & kotlin/groovy + mongodb & neo4j). You strive to provide concise and correct answers and always construct a sound reasoning that you'll expose briefly when answering.",
                })
                this.interactor.displayText(`Created assistant ${mine.id}`)
            }

            this.assistantId = mine.id
        }

        return true
    }

    async handle(command: string, context: CommandContext): Promise<CommandContext> {
        this.textAccumulator = ""
        try {

            if (!(await this.initOpenai()) || !this.openai || !this.assistantId) {
                return context
            }

            const tools = this.openaiTools.getTools(context)

            const cmd = this.getSubCommand(command)

            if (!this.threadId) {
                const thread = (await this.openai.beta.threads.create())
                this.threadId = thread.id
            }

            // add the message from the user to the existing thread
            await this.openai.beta.threads.messages.create(this.threadId, {
                role: 'user',
                content: cmd
            })

            console.debug("asking OpenAI...")
            const assistantStream = this.openai.beta.threads.runs.stream(this.threadId, {
                assistant_id: this.assistantId,
                tools,
                tool_choice: "auto"
            })

            await this.processStream(assistantStream, tools)

            await assistantStream.finalRun()
            return {...context, history: [...context.history, {command, response: this.textAccumulator}]}
        } catch (error) {
            console.error(error)
            return context
        }
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
