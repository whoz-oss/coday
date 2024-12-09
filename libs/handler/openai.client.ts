import OpenAI from 'openai'
import { AiClient, Interactor, ModelSize } from '../model'
import { CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '../shared'
import { AiThread } from '../ai-thread/ai-thread'
import { Agent } from '../model/agent'
import { Observable, of, Subject } from 'rxjs'
import { ThreadMessage } from '../ai-thread/ai-thread.types'
import { ChatCompletionMessageParam, ChatCompletionSystemMessageParam } from 'openai/resources/chat/completions'

const OpenaiModels = {
  [ModelSize.BIG]: {
    name: 'gpt-4o',
    contextWindow: 128000,
    price: {
      inputMTokens: 2.5,
      cacheRead: 1.25,
      outputMTokens: 10,
    },
  },
  [ModelSize.SMALL]: {
    name: 'gpt-4o-mini',
    contextWindow: 128000,
    price: {
      inputMTokens: 0.15,
      cacheRead: 0.075,
      outputMTokens: 0.6,
    },
  },
}

export class OpenaiClient extends AiClient {
  constructor(
    readonly interactor: Interactor,
    private apiKeyProvider: () => string | undefined,
    /**
     * Custom apiUrl for providers using Openai SDK
     * @private
     */
    private apiUrl: string | undefined = undefined,
    /**
     * Custom model description for providers using Openai SDK
     * @private
     */
    private models: any = OpenaiModels,
    /**
     * Custom provider name
     * @private
     */
    private providerName: string = 'OpenAI'
  ) {
    super()
  }

  async run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    const openai = this.isOpenaiReady()
    if (!openai) return of()

    thread.data.openai = {
      price: 0,
    }
    thread.resetUsageForRun()

    const outputSubject: Subject<CodayEvent> = new Subject()
    const thinking = setInterval(() => this.interactor.thinking(), this.thinkingInterval)
    this.processThread(openai, agent, thread, outputSubject).finally(() => {
      clearInterval(thinking)
      this.showAgent(agent, this.providerName, this.models[this.getModelSize(agent)].name)
      this.showUsage(thread)
      outputSubject.complete()
    })
    return outputSubject
  }

  private async processThread(
    client: OpenAI,
    agent: Agent,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    try {
      const model = this.models[this.getModelSize(agent)]
      const initialContextCharLength = agent.systemInstructions.length + agent.tools.charLength + 20
      const charBudget = model.contextWindow * this.charsPerToken - initialContextCharLength

      const response = await client.chat.completions.create({
        model: model.name,
        messages: this.toOpenAiMessage(agent, thread.getMessages(charBudget)),
        tools: agent.tools.getTools(),
        max_completion_tokens: undefined,
        temperature: agent.definition.temperature ?? 0.8,
      })

      this.updateUsage(response.usage, agent, thread)

      if (response.choices[0].finish_reason === 'length') throw new Error('Max tokens reached for Openai 😬')

      const text = response.choices[0].message.content?.trim()
      this.handleText(thread, text, agent, subscriber)

      const toolRequests = response.choices[0].message?.tool_calls?.map(
        (toolCall) =>
          new ToolRequestEvent({
            toolRequestId: toolCall.id,
            name: toolCall.function.name,
            args: toolCall.function.arguments,
          })
      )

      if (await this.shouldProcessAgainAfterResponse(text, toolRequests, agent, thread)) {
        // then tool responses to send
        await this.processThread(client, agent, thread, subscriber)
      }
    } catch (error: any) {
      subscriber.next(
        new ErrorEvent({
          error,
        })
      )
    }
  }

  private updateUsage(usage: any, agent: Agent, thread: AiThread): void {
    const cacheReadTokens = usage?.prompt_tokens_details?.cached_tokens ?? 0
    const inputNoCacheTokens = (usage?.prompt_tokens ?? 0) - cacheReadTokens // TODO: check again with doc...
    const input = inputNoCacheTokens * this.models[this.getModelSize(agent)].price.inputMTokens
    const outputTokens = usage?.completion_tokens ?? 0
    const output = outputTokens * this.models[this.getModelSize(agent)].price.outputMTokens
    const cacheRead = cacheReadTokens * this.models[this.getModelSize(agent)].price.cacheRead
    const price = (input + output + cacheRead) / 1_000_000

    thread.addUsage({
      input: inputNoCacheTokens,
      output: outputTokens,
      cache_read: cacheReadTokens,
      cache_write: 0, // cannot deduce it as not given and not priced in documentation
      price,
    })
  }

  private isOpenaiReady(): OpenAI | undefined {
    const apiKey = this.apiKeyProvider()
    if (!apiKey) {
      this.interactor.warn('OPENAI_API_KEY not set, skipping AI command')
      return
    }

    return new OpenAI({
      apiKey,
      /**
       * Possible customization for third parties using Openai SDK (Google Gemini, xAi, ...)
       */
      baseURL: this.apiUrl,
    })
  }

  private toOpenAiMessage(agent: Agent, messages: ThreadMessage[]): ChatCompletionMessageParam[] {
    const systemInstructionMessage: ChatCompletionSystemMessageParam = {
      content: agent.systemInstructions,
      role: 'system',
    }
    const openaiMessages = messages
      .map((msg) => {
        let openaiMessage: ChatCompletionMessageParam | undefined
        if (msg instanceof MessageEvent) {
          const role = msg.role === 'assistant' ? 'system' : 'user'
          openaiMessage = {
            role,
            content: msg.content,
            name: msg.role === 'user' ? msg.name : undefined,
          }
        }
        if (msg instanceof ToolRequestEvent) {
          openaiMessage = {
            role: 'assistant',
            name: agent.name,
            tool_calls: [
              {
                type: 'function',
                id: msg.toolRequestId,
                function: {
                  name: msg.name,
                  arguments: msg.args,
                },
              },
            ],
          }
        }
        if (msg instanceof ToolResponseEvent) {
          openaiMessage = {
            role: 'tool',
            content: msg.output,
            tool_call_id: msg.toolRequestId,
          }
        }
        return openaiMessage
      })
      .filter((m) => !!m)

    return [systemInstructionMessage, ...openaiMessages]
  }

  // Agreed, one should not comment code...
  // ...but suffered so much on it, will keep it until there is a viable solution for calling openai's assistants

  // async isReady(
  //   assistantName: string,
  //   context: CommandContext,
  // ): Promise<boolean> {
  //   if (!this.isOpenaiReady()) {
  //     return false
  //   }
  //
  //   this.assistant = await this.findAssistant(assistantName, context)
  //
  //   await this.createThread(context)
  //
  //   return true
  // }

  // async answer(
  //   name: string,
  //   command: string,
  //   context: CommandContext,
  // ): Promise<string> {
  //   const assistantName = name.toLowerCase()
  //
  //   this.textAccumulator = ""
  //   if (!(await this.isReady(assistantName, context))) {
  //     return "Openai client not ready"
  //   }
  //
  //   const tools = this.toolBox.getTools(context)
  //   // Create new toolSet instance with current context tools
  //   const toolSet = new ToolSet(tools)
  //   const threadId = context.data.openaiData.threadId
  //
  //   await this.openai!.beta.threads.messages.create(threadId!, {
  //     role: "user",
  //     content: command,
  //   })
  //   const assistantStream = this.openai!.beta.threads.runs.stream(
  //     threadId!,
  //     {
  //       assistant_id: this.assistant!.id,
  //       tools: [...tools, {type: "file_search"}],
  //       tool_choice: "auto",
  //       max_completion_tokens: 120000,
  //       max_prompt_tokens: 120000,
  //       parallel_tool_calls: false,
  //     },
  //   )
  //
  //   await this.processStream(assistantStream, toolSet, threadId)
  //
  //   await assistantStream.finalRun()
  //
  //   return this.textAccumulator
  // }

  // private async processStream(stream: AssistantStream, toolSet: ToolSet, threadId: string) {
  //   // Check interruption before starting stream processing
  //   stream.on("textDone", (diff) => {
  //     this.interactor.displayText(diff.value, this.assistant?.name)
  //     this.textAccumulator += diff.value
  //   })
  //   for await (const chunk of stream) {
  //     this.interactor.thinking()
  //     if (chunk.event === "thread.run.requires_action") {
  //       try {
  //         const toolCalls =
  //           chunk.data.required_action?.submit_tool_outputs.tool_calls ?? []
  //         const toolOutputs = await Promise.all(
  //           toolCalls.map(async (call) => {
  //             const toolCall: ToolCall = {
  //               id: call.id,
  //               name: call.function.name,
  //               args: call.function.arguments
  //             }
  //             const toolRequest = new ToolRequestEvent(toolCall)
  //             const output = await toolSet.runTool(toolRequest)
  //             return ({tool_call_id: call.id, output})
  //           }),
  //         )
  //
  //         const newStream =
  //           this.openai!.beta.threads.runs.submitToolOutputsStream(
  //             threadId!,
  //             chunk.data.id,
  //             {tool_outputs: toolOutputs},
  //           )
  //         if (this.killed) return
  //         await this.processStream.call(this, newStream, toolSet, threadId)
  //       } catch (error) {
  //         console.error(`Error processing tool call`, error)
  //       }
  //     }
  //   }
  // }

  // private async createThread(context: CommandContext): Promise<void> {
  //   const openaiData = context.data.openaiData
  //   if (!openaiData) {
  //     context.data.openaiData = {}
  //   }
  //
  //   const threadId = context.data.openaiData?.threadId
  //   if (!threadId) {
  //     const thread = await this.openai!.beta.threads.create()
  //     context.data.openaiData.threadId = thread.id
  //     this.interactor.displayText(`Thread created with ID: ${thread.id}`)
  //
  //     await this.openai!.beta.threads.messages.create(thread.id, {
  //       role: "assistant",
  //       content: context.project.description,
  //     })
  //   }
  // }

  // TODO: move this out, it should be on the project object rather than here
  //   private async initAssistantList(): Promise<void> {
  //     // init map name -> id
  //     if (!this.assistants.length) {
  //       let after: string | undefined = undefined
  //       do {
  //         const fetchedAssistants: Assistant[] = (
  //           await this.openai!.beta.assistants.list({
  //             order: "asc",
  //             after,
  //             limit: 100,
  //           }).withResponse()
  //         ).data.getPaginatedItems()
  //         this.assistants.push(
  //           ...fetchedAssistants
  //             .filter((a) => !!a.name)
  //             .map((a) => ({
  //               name: a.name as string,
  //               id: a.id,
  //             })),
  //         )
  //         after =
  //           fetchedAssistants.length > 0
  //             ? fetchedAssistants[fetchedAssistants.length - 1].id
  //             : undefined
  //       } while (after)
  //     }
  //   }

  // private async findAssistant(
  //   name: string,
  //   context: CommandContext,
  // ): Promise<AssistantReference> {
  //   await this.initAssistantList()
  //
  //   // then find all names that could match the given one
  //   const matchingAssistants = this.assistants.filter((a) =>
  //     a.name.toLowerCase().startsWith(name),
  //   )
  //
  //   if (matchingAssistants.length === 1) {
  //     return matchingAssistants[0]
  //   }
  //
  //   if (matchingAssistants.length > 1) {
  //     const selection = await this.interactor.chooseOption(
  //       matchingAssistants.map((m) => m.name),
  //       "Choose an assistant",
  //       `Found ${matchingAssistants.length} assistants that start with ${name}/`,
  //     )
  //     return matchingAssistants.find((m) => m.name === selection)!
  //   }
  //
  //   // no existing assistant found, let's check the project ones that do have systemInstructions
  //   const projectAssistants = context.project.assistants
  //     ? [DEFAULT_DESCRIPTION, ...context.project.assistants]
  //     : undefined
  //   const matchingProjectAssistants = projectAssistants?.filter(
  //     (a) => a.name.toLowerCase().startsWith(name) && !!a.systemInstructions,
  //   )
  //
  //   if (!matchingProjectAssistants?.length) {
  //     throw new Error("No matching assistant")
  //   }
  //
  //   let assistantToCreate: AssistantDescription | undefined
  //   if (matchingProjectAssistants?.length === 1) {
  //     assistantToCreate = matchingProjectAssistants[0]
  //   }
  //   if (matchingProjectAssistants?.length > 1) {
  //     const selection = await this.interactor.chooseOption(
  //       matchingAssistants.map((m) => m.name),
  //       "Choose an assistant",
  //       `Found ${matchingProjectAssistants?.length} project assistants that start with ${name}/`,
  //     )
  //     assistantToCreate = matchingProjectAssistants.find(
  //       (m) => m.name === selection,
  //     )
  //   }
  //
  //   if (!assistantToCreate) {
  //     throw new Error("No matching assistant")
  //   }
  //
  //   this.interactor.displayText(
  //     `No existing assistant found for ${name}, will try to create it`,
  //   )
  //
  //
  //   const createdAssistant = await this.openai!.beta.assistants.create({
  //     name: assistantToCreate?.name,
  //     model: assistantToCreate.model ?? DEFAULT_MODEL,
  //     instructions: assistantToCreate?.systemInstructions,
  //     temperature: assistantToCreate.temperature ?? DEFAULT_TEMPERATURE,
  //   })
  //   this.interactor.displayText(`Created assistant ${createdAssistant.id}`)
  //   const createdReference: AssistantReference = {
  //     name: assistantToCreate.name,
  //     id: createdAssistant.id,
  //   }
  //   this.assistants.push(createdReference)
  //
  //   return createdReference
  // }
}
