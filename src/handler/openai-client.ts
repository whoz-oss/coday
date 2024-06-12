import {Interactor} from "../interactor";
import OpenAI from "openai";
import {AssistantStream} from "openai/lib/AssistantStream";
import {Beta} from "openai/resources";
import {JiraTools} from "./jira-tools";
import {GitTools} from "./git-tools";
import {OpenaiTools} from "./openai-tools";
import {Tool} from "./init-tools";
import {ScriptsTools} from "./scripts-tools";
import {CommandContext} from "../command-context";
import Assistant = Beta.Assistant;

const ASSISTANT_INSTRUCTIONS = `
You are Coday, an AI assistant used interactively by users through a chat or alike interface.
By providing a sound and logic reasoning, you answer concisely to the user requests.
You are curious and will use the provided functions to gather a bit more knowledge than you need to answer the request.
Unless explicitly asked for, keep your answers short.`

type AssistantReference = { name: string, id: string }

export class OpenaiClient {
    openai: OpenAI | undefined
    threadId: string | null = null
    textAccumulator: string = ""

    openaiTools: OpenaiTools
    jiraTools: JiraTools
    gitTools: GitTools
    scriptTools: ScriptsTools
    apiKey: string | undefined
    assistants: AssistantReference[] = []
    assistant: AssistantReference | undefined

    constructor(private interactor: Interactor, private apiKeyProvider: () => string | undefined) {
        this.openaiTools = new OpenaiTools(interactor)
        this.jiraTools = new JiraTools(interactor)
        this.gitTools = new GitTools(interactor)
        this.scriptTools = new ScriptsTools(interactor)
    }

    async isReady(assistantName: string, context: CommandContext): Promise<boolean> {
        this.apiKey = this.apiKeyProvider()
        if (!this.apiKey) {
            this.interactor.warn('OPENAI_API_KEY env var not set, skipping AI command')
            return false
        }

        if (!this.openai) {
            this.openai = new OpenAI({
                apiKey: this.apiKey,
            })
        }

        this.assistant = await this.findAssistant(assistantName, context)

        if (!this.threadId) {
            const thread = (await this.openai.beta.threads.create())
            this.threadId = thread.id

            await this.openai.beta.threads.messages.create(this.threadId, {
                role: 'assistant',
                content: `Specific project context: ${context.project.description}`
            })
        }

        return true
    }

    async answer(name: string, command: string, context: CommandContext): Promise<string> {
        const assistantName = name.toLowerCase()

        this.textAccumulator = ""
        if (!await this.isReady(assistantName, context)) {
            return "Openai client not ready"
        }

        const tools = [
            ...this.openaiTools.getTools(context),
            ...this.jiraTools.getTools(context),
            ...this.gitTools.getTools(context)
        ]

        await this.openai!.beta.threads.messages.create(this.threadId!, {
            role: 'user',
            content: command
        })
        const assistantStream = this.openai!.beta.threads.runs.stream(this.threadId!, {
            assistant_id: this.assistant!.id,
            tools,
            tool_choice: "auto"
        })

        await this.processStream(assistantStream, tools)

        await assistantStream.finalRun()

        return this.textAccumulator
    }

    async processStream(stream: AssistantStream, tools: Tool[]) {
        stream.on('textDone', (diff) => {
            this.interactor.displayText(diff.value, this.assistant?.name)
            this.textAccumulator += diff.value
        })
        for await (const chunk of stream) {
            if (chunk.event === "thread.run.requires_action") {
                try {
                    const toolCalls = chunk.data.required_action?.submit_tool_outputs.tool_calls ?? []
                    const toolOutputs = await Promise.all(toolCalls.map(async (toolCall) => {
                        let output
                        const funcWrapper = tools.find(t => t.function.name === toolCall.function.name)
                        if (!funcWrapper) {
                            output = `Function ${toolCall.function.name} not found.`
                            return {tool_call_id: toolCall.id, output}
                        }

                        const toolFunc = funcWrapper.function.function

                        try {
                            let args: any = JSON.parse(toolCall.function.arguments)

                            if (!Array.isArray(args)) {
                                args = [args]
                            }
                            output = await toolFunc.apply(null, args)
                        } catch (err) {
                            this.interactor.error(err)
                            output = `Error on executing function, got error: ${JSON.stringify(err)}`
                        }

                        if (!output) {
                            output = `Tool function ${funcWrapper.function.name} finished without error.`
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

                    await this.processStream.call(this, newStream, tools)
                } catch (error) {
                    console.error(`Error processing tool call`, error)
                }
            }
        }
    }

    reset(): void {
        this.threadId = null
        this.interactor.displayText("Thread has been reset")
    }

    private async findAssistant(name: string, context: CommandContext): Promise<AssistantReference> {

        // init map name -> id
        if (!this.assistants.length) {
            let after: string | undefined = undefined
            do {
                const fetchedAssistants: Assistant[] = (await this.openai!.beta.assistants.list({
                        order: 'asc',
                        after,
                        limit: 100,
                    }).withResponse()
                )
                    .data
                    .getPaginatedItems()
                this.assistants.push(...fetchedAssistants.filter(a => !!a.name).map(a => ({
                    name: a.name as string,
                    id: a.id
                })))
                after = fetchedAssistants.length > 0 ? fetchedAssistants[fetchedAssistants.length - 1].id : undefined;
            } while (after);
        }

        // then find all names that could match the given one
        const matchingAssistants = this.assistants.filter(a => a.name.toLowerCase().startsWith(name))

        if (matchingAssistants.length === 1) {
            return matchingAssistants[0]
        }
        if (matchingAssistants.length > 1) {
            const selection = this.interactor.chooseOption(matchingAssistants.map(m => m.name), "Type the assistant number you want", `Found ${matchingAssistants.length} assistants that start with ${name}/`)
            const index = parseInt(selection)
            return matchingAssistants[index]
        }
        throw new Error("no matching assistant")

        // TODO: adapt this previous fallback on creating the assistant when not existing but on the project assistant description
        // if (!mine) {
        //     mine = await this.openai.beta.assistants.create({
        //         name: `Coday_alpha`,
        //         model: "gpt-4o",
        //         instructions: ASSISTANT_INSTRUCTIONS,
        //     })
        //     this.interactor.displayText(`Created assistant ${mine.id}`)
        // }

    }
}
