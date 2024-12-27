import OpenAI from 'openai'
import { AiClient, Interactor, ModelSize } from '../model'
import { CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '../shared'
import { AiThread } from '../ai-thread/ai-thread'
import { Agent } from '../model/agent'
import { Observable, of, Subject } from 'rxjs'
import { ThreadMessage } from '../ai-thread/ai-thread.types'
import { ChatCompletionMessageParam, ChatCompletionSystemMessageParam } from 'openai/resources/chat/completions'
import { MessageCreateParams } from 'openai/src/resources/beta/threads/messages'
import { AssistantStream } from 'openai/lib/AssistantStream'
import { RunSubmitToolOutputsParams } from 'openai/resources/beta/threads/runs/runs'

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

type AssistantThreadData = {
  threadId?: string
  lastTimestamp?: string
}

export class OpenaiClient extends AiClient {
  constructor(
    readonly name: string,
    readonly interactor: Interactor,
    private apiKey: string | undefined,
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
    thread.resetUsageForRun()
    if (agent.definition.openaiAssistantId) {
      // then use the stateful assistant API
      return this.runAssistant(agent, thread)
    }

    const openai = this.isOpenaiReady()
    if (!openai) return of()

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

  async runAssistant(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    const openai = this.isOpenaiReady()
    if (!openai) return of()

    const outputSubject: Subject<CodayEvent> = new Subject()

    // init or reset
    thread.data.openai = {
      price: thread.data?.openai?.price ?? 0,
      runPrice: 0,
      assistantThreadData: thread.data?.openai?.assistantThreadData ?? {},
    }

    const threadData: AssistantThreadData = thread.data.openai.assistantThreadData
    const thinking = setInterval(() => this.interactor.thinking(), 3000)

    // Create assistant thread if not existing
    if (!threadData.threadId) {
      const assistantThread = await openai.beta.threads.create()
      this.interactor.displayText('Assistant thread created')
      threadData.threadId = assistantThread.id
    }

    const messages = thread.getMessages()
    const lastMessageIndex = threadData.lastTimestamp
      ? messages.findIndex((m) => m.timestamp >= threadData.lastTimestamp!)
      : -1
    const messagesToUpload = messages.slice(lastMessageIndex + 1)

    this.updateAssistantThread(openai, thread, messagesToUpload)
      .then(async () => await this.processAssistantThread(openai, agent, thread, outputSubject))
      .finally(() => {
        clearInterval(thinking)
        this.showAgent(agent, this.providerName, this.models[this.getModelSize(agent)].name)
        this.showUsage(thread)
        outputSubject.complete()
      })
    return outputSubject
  }

  protected async processAssistantThread(
    client: OpenAI,
    agent: Agent,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    const assistantStream = client.beta.threads.runs.stream(thread.data.openai.assistantThreadData.threadId, {
      assistant_id: agent.definition.openaiAssistantId!,
      tools: [...agent.tools.getTools(), { type: 'file_search' }],
      tool_choice: 'auto',
      max_completion_tokens: 120000,
      max_prompt_tokens: 120000,
      parallel_tool_calls: false,
    })

    await this.processAssistantStream(assistantStream, agent, client, thread, subscriber)
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
    if (!this.apiKey) {
      this.interactor.warn('OPENAI_API_KEY not set, skipping AI command')
      return
    }

    return new OpenAI({
      apiKey: this.apiKey,
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

  private async updateAssistantThread(
    client: OpenAI,
    thread: AiThread,
    messagesToUpload: ThreadMessage[]
  ): Promise<void> {
    // WARNING: this part sucks, OpenAI API not allowing multiple messages at once T_T, so forced iterations
    this.interactor.displayText(`${messagesToUpload.length} messages to upload to assistant thread...`)
    for (const messagesToUploadElement of messagesToUpload) {
      if (!this.shouldProceed(thread)) {
        throw new Error('Assistant thread update interrupted')
      }
      const assistantMessage = this.toAssistantMessage(messagesToUploadElement)
      await client.beta.threads.messages.create(thread.data.openai.assistantThreadData.threadId, assistantMessage)
      // update the marker in case of interruption to resume at last stop
      thread.data.openai.assistantThreadData.lastTimestamp = messagesToUploadElement.timestamp
    }
    this.interactor.displayText(`Messages uploaded.`)
  }

  private toAssistantMessage(m: ThreadMessage): MessageCreateParams {
    if (m instanceof MessageEvent) {
      return {
        role: m.role,
        content: m.content,
      }
    }
    if (m instanceof ToolResponseEvent) {
      return {
        role: 'user',
        content: `Here is the result of : \n<toolRequestId>${m.toolRequestId}</toolRequestId>\n<output>${m.output}</output>`,
      }
    }
    return {
      role: 'assistant',
      content: `${m.name}: Can you provide me the result of this :\n<toolRequestId>${m.toolRequestId}</toolRequestId>\n<function>${m.name}</function>\n<args>${m.args}</args>`,
    }
  }

  private async processAssistantStream(
    stream: AssistantStream,
    agent: Agent,
    client: OpenAI,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    // Check interruption before starting stream processing
    stream.on('textDone', (diff) => {
      thread.data.openai.assistantThreadData.lastTimestamp = this.handleText(thread, diff.value, agent, subscriber)
    })
    for await (const chunk of stream) {
      this.interactor.thinking()
      if (chunk.event === 'thread.run.completed') {
        const data = chunk.data as unknown as any
        this.updateUsage(data?.usage, agent, thread)
      }

      if (chunk.event === 'thread.run.requires_action') {
        try {
          const toolRequests =
            chunk.data.required_action?.submit_tool_outputs.tool_calls?.map(
              (toolCall) =>
                new ToolRequestEvent({
                  toolRequestId: toolCall.id,
                  name: toolCall.function.name,
                  args: toolCall.function.arguments,
                })
            ) ?? []
          const toolOutputs: RunSubmitToolOutputsParams.ToolOutput[] = []
          await Promise.all(
            toolRequests.map(async (request) => {
              let responseEvent: ToolResponseEvent
              try {
                responseEvent = await agent.tools.run(request)
              } catch (error: any) {
                console.error(`Error running tool ${request.name}:`, error)
                responseEvent = request.buildResponse(`Error: ${error.message}`)
              }
              thread.addToolRequests(agent.name, [request])
              thread.addToolResponseEvents([responseEvent])
              toolOutputs.push({
                tool_call_id: request.toolRequestId,
                output: responseEvent.output,
              })
            })
          )

          const newStream = client!.beta.threads.runs.submitToolOutputsStream(
            thread.data.openai.assistantThreadData.threadId!,
            chunk.data.id,
            { tool_outputs: toolOutputs }
          )
          if (!this.shouldProceed(thread)) return
          await this.processAssistantStream.call(this, newStream, agent, client, thread, subscriber)
        } catch (error) {
          console.error(`Error processing tool call`, error)
        }
      }
    }
  }
}
