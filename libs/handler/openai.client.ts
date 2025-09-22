import OpenAI from 'openai'
import { Agent, AiClient, AiModel, AiProviderConfig, CompletionOptions, Interactor } from '../model'
import { CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
import { AiThread } from '../ai-thread/ai-thread'
import { Observable, Subject } from 'rxjs'
import { ThreadMessage } from '../ai-thread/ai-thread.types'
import { ChatCompletionMessageParam, ChatCompletionSystemMessageParam } from 'openai/resources/chat/completions'
import { MessageCreateParams } from 'openai/resources/beta/threads/messages'
import { AssistantStream } from 'openai/lib/AssistantStream'
import { RunSubmitToolOutputsParams } from 'openai/resources/beta/threads/runs/runs'
import { CodayLogger } from '../service/coday-logger'

type AssistantThreadData = {
  threadId?: string
  lastTimestamp?: string
}

const OPENAI_DEFAULT_MODELS: AiModel[] = [
  {
    name: 'gpt-4.1-2025-04-14',
    contextWindow: 1000000,
    alias: 'BIG',
    price: {
      inputMTokens: 2,
      cacheRead: 0.5,
      outputMTokens: 8,
    },
  },
  {
    name: 'gpt-4o-mini',
    alias: 'SMALL',
    contextWindow: 128000,
    price: {
      inputMTokens: 0.15,
      cacheRead: 0.075,
      outputMTokens: 0.6,
    },
  },
]

export class OpenaiClient extends AiClient {
  name: string

  constructor(
    readonly interactor: Interactor,
    aiProviderConfig: AiProviderConfig,
    logger: CodayLogger
  ) {
    super(aiProviderConfig, logger)
    this.mergeModels(OPENAI_DEFAULT_MODELS)
    if (aiProviderConfig.name.toLowerCase() !== 'openai') {
      this.models = aiProviderConfig.models ?? []
    }
    this.name = aiProviderConfig.name
  }

  async run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    thread.resetUsageForRun()

    const openai = this.isOpenaiReady()
    if (!openai) return this.returnError('Client not ready')
    const model = this.getModel(agent)
    if (!model) return this.returnError(`Model not found for agent ${agent.name}`)

    if (agent.definition.openaiAssistantId) {
      // then use the stateful assistant API
      return this.runAssistant(agent, model, thread)
    }

    const outputSubject: Subject<CodayEvent> = new Subject()
    const thinking = setInterval(() => this.interactor.thinking(), this.thinkingInterval)
    this.processThread(openai, agent, model, thread, outputSubject).finally(() => {
      clearInterval(thinking)
      this.showAgentAndUsage(agent, this.aiProviderConfig.name, model.name, thread)
      // Log usage after the complete response cycle
      const cost = thread.usage?.price || 0
      this.logAgentUsage(agent, model.name, cost)
      outputSubject.complete()
    })
    return outputSubject
  }

  async runAssistant(agent: Agent, model: AiModel, thread: AiThread): Promise<Observable<CodayEvent>> {
    const openai = this.isOpenaiReady()
    if (!openai) return this.returnError('Client not ready')

    const outputSubject: Subject<CodayEvent> = new Subject()

    // init or reset
    thread.data.openai = {
      price: thread.data?.openai?.price ?? 0,
      runPrice: 0,
      assistantThreadData: thread.data?.openai?.assistantThreadData ?? {},
    }

    const charBudget =
      model.contextWindow * this.charsPerToken - agent.systemInstructions.length - agent.tools.charLength
    const data = await this.getMessages(thread, charBudget, model.name)
    if (data.compacted) {
      // then need to reset the assistant thread as all the beginning is compacted
      thread.data.openai.assistantThreadData = {}
    }

    const threadData: AssistantThreadData = thread.data.openai.assistantThreadData
    const thinking = setInterval(() => this.interactor.thinking(), 3000)

    // Create assistant thread if not existing
    if (!threadData.threadId) {
      const assistantThread = await openai.beta.threads.create()
      this.interactor.displayText('Assistant thread created')
      threadData.threadId = assistantThread.id
    }

    const messages = data.messages
    const lastMessageIndex = threadData.lastTimestamp
      ? messages.findIndex((m) => m.timestamp >= threadData.lastTimestamp!)
      : -1
    const messagesToUpload = messages.slice(lastMessageIndex + 1)

    this.updateAssistantThread(openai, thread, messagesToUpload)
      .then(async () => await this.processAssistantThread(openai, agent, model, thread, outputSubject))
      .finally(() => {
        clearInterval(thinking)
        this.showAgentAndUsage(agent, this.aiProviderConfig.name, model.name, thread)
        // Log usage after the complete response cycle
        const cost = thread.usage?.price || 0
        this.logAgentUsage(agent, model.name, cost)
        outputSubject.complete()
      })
    return outputSubject
  }

  protected async processAssistantThread(
    client: OpenAI,
    agent: Agent,
    model: AiModel,
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

    await this.processAssistantStream(assistantStream, agent, model, client, thread, subscriber)
  }

  private async processThread(
    client: OpenAI,
    agent: Agent,
    model: AiModel,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    try {
      // Recalculate budget on each iteration to account for growing thread
      const initialContextCharLength = agent.systemInstructions.length + agent.tools.charLength + 20
      const charBudget = model.contextWindow * this.charsPerToken - initialContextCharLength

      const data = await this.getMessages(thread, charBudget, model.name)

      const response = await client.chat.completions.create({
        model: model.name,
        messages: this.toOpenAiMessage(agent, data.messages),
        tools: agent.tools.getTools(),
        max_completion_tokens: undefined,
        temperature: agent.definition.temperature ?? 0.8,
      })
      2
      this.updateUsage(response.usage, agent, model, thread)

      const firstChoice = response.choices[0]!

      if (firstChoice.finish_reason === 'length') throw new Error('Max tokens reached for Openai 😬')

      const text = firstChoice.message.content?.trim()
      this.handleText(thread, text, agent, subscriber)

      const toolRequests = firstChoice.message?.tool_calls?.map(
        (toolCall) =>
          new ToolRequestEvent({
            toolRequestId: toolCall.id,
            name: toolCall.function.name,
            args: toolCall.function.arguments,
          })
      )

      if (await this.shouldProcessAgainAfterResponse(text, toolRequests, agent, thread)) {
        // then tool responses to send
        await this.processThread(client, agent, model, thread, subscriber)
      }
    } catch (error: any) {
      this.handleError(error, subscriber, this.aiProviderConfig.name)
    }
  }

  private updateUsage(usage: any, _agent: Agent, model: AiModel, thread: AiThread): void {
    const cacheReadTokens = usage?.prompt_tokens_details?.cached_tokens ?? 0
    const inputNoCacheTokens = (usage?.prompt_tokens ?? 0) - cacheReadTokens // TODO: check again with doc...
    const input = inputNoCacheTokens * (model?.price?.inputMTokens ?? 0)
    const outputTokens = usage?.completion_tokens ?? 0
    const output = outputTokens * (model?.price?.outputMTokens ?? 0)
    const cacheRead = cacheReadTokens * (model?.price?.cacheRead ?? 0)
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
    if (!this.aiProviderConfig.apiKey) {
      this.interactor.warn(
        `${this.aiProviderConfig.name}_API_KEY not set, skipping AI command. Please configure your API key.`
      )
      return
    }

    try {
      return new OpenAI({
        apiKey: this.aiProviderConfig.apiKey,
        /**
         * Possible customization for third parties using Openai SDK (Google Gemini, xAi, ...)
         */
        baseURL: this.aiProviderConfig.url,
      })
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.warn(`Failed to initialize ${this.aiProviderConfig.name} client: ${errorMessage}`)
      console.error(`${this.aiProviderConfig.name} client initialization error:`, error)
      return
    }
  }

  private toOpenAiMessage(agent: Agent, messages: ThreadMessage[]): ChatCompletionMessageParam[] {
    const systemInstructionMessage: ChatCompletionSystemMessageParam = {
      content: agent.systemInstructions,
      role: 'system',
    }

    const openaiMessages = messages.flatMap((msg, index): ChatCompletionMessageParam[] => {
      if (msg instanceof MessageEvent) {
        const isLastUserMessage = msg.role === 'user' && index === messages.length - 1
        const content = this.enhanceWithCurrentDateTime(msg.content, isLastUserMessage)

        // Convert rich content to OpenAI format
        const openaiContent: string | OpenAI.ChatCompletionContentPart[] =
          content.map((c) => {
                if (c.type === 'text') {
                  return { type: 'text' as const, text: c.content }
                }
                if (c.type === 'image') {
                  const image = {
                    type: 'image_url' as const,
                    image_url: {
                      url: `data:${c.mimeType};base64,${c.content}`,
                      detail: 'auto' as const, // Let OpenAI choose the appropriate detail level
                    },
                  }
                  console.log(`got an image in message event`)
                  return image
                }
                throw new Error(`Unknown content type: ${(c as any).type}`)
              })

        if (msg.role === 'assistant') {
          return [
            {
              role: 'assistant' as const,
              content:
                typeof openaiContent === 'string'
                  ? openaiContent
                  : openaiContent.map((c) => (c.type === 'text' ? c.text : '[Image]')).join(' '),
              name: agent.name,
            },
          ]
        } else {
          return [
            {
              role: 'user' as const,
              content: openaiContent,
              name: msg.name,
            },
          ]
        }
      }

      if (msg instanceof ToolRequestEvent) {
        return [
          {
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
          },
        ]
      }

      if (msg instanceof ToolResponseEvent) {
        if (typeof msg.output === 'string') {
          // Simple string output
          return [
            {
              role: 'tool',
              content: msg.output,
              tool_call_id: msg.toolRequestId,
            },
          ]
        } else {
          // Rich content (MessageContent) - OpenAI workaround needed for images
          const content = msg.output

          if (content.type === 'image') {
            // Return two messages: tool response + user message with image
            return [
              {
                role: 'tool',
                content: 'Image retrieved successfully. See following message.',
                tool_call_id: msg.toolRequestId,
              },
              {
                role: 'user',
                content: [
                  {
                    type: 'image_url' as const,
                    image_url: {
                      url: `data:${content.mimeType};base64,${content.content}`,
                      detail: 'auto' as const,
                    },
                  },
                ],
                name: 'system', // Indicate this is a system-generated message
              },
            ]
          } else if (content.type === 'text') {
            // Text content
            return [
              {
                role: 'tool',
                content: content.content,
                tool_call_id: msg.toolRequestId,
              },
            ]
          } else {
            throw new Error(`Unknown content type: ${(content as any).type}`)
          }
        }
      }

      return [] // Should not happen, but satisfies TypeScript
    })

    return [systemInstructionMessage, ...openaiMessages]
  }

  private async updateAssistantThread(
    client: OpenAI,
    thread: AiThread,
    messagesToUpload: ThreadMessage[]
  ): Promise<void> {
    try {
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
    } catch (error) {
      console.error('Error updating assistant thread:', error)
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.displayText(`\u26a0\ufe0f Error updating assistant thread: ${errorMessage}`)
      throw error // Re-throw to be caught by the calling function
    }
  }

  private toAssistantMessage(m: ThreadMessage): MessageCreateParams {
    if (m instanceof MessageEvent) {
      // For assistant API, convert rich content to text representation
      const content =
        typeof m.content === 'string'
          ? m.content
          : m.content.filter(c => c.type === 'text').map(c => c.content).join('\n')

      return {
        role: m.role,
        content,
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
    model: AiModel,
    client: OpenAI,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    try {
      // Check interruption before starting stream processing
      stream.on('textDone', (diff) => {
        thread.data.openai.assistantThreadData.lastTimestamp = this.handleText(thread, diff.value, agent, subscriber)
      })
      for await (const chunk of stream) {
        this.interactor.thinking()
        if (chunk.event === 'thread.run.completed') {
          const data = chunk.data as unknown as any
          this.updateUsage(data?.usage, agent, model, thread)
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
                  this.interactor.sendEvent(request)
                  responseEvent = await agent.tools.run(request)
                } catch (error: any) {
                  console.error(`Error running tool ${request.name}:`, error)
                  responseEvent = request.buildResponse(`Error: ${error.message}`)
                }
                this.interactor.sendEvent(responseEvent)
                thread.addToolRequests(agent.name, [request])
                thread.addToolResponseEvents([responseEvent])
                toolOutputs.push({
                  tool_call_id: request.toolRequestId,
                  // TODO: see if we cannot use a MessageContent for toolOutput ?
                  output: responseEvent.getTextOutput(),
                })
              })
            )

            const newStream = client!.beta.threads.runs.submitToolOutputsStream(
              thread.data.openai.assistantThreadData.threadId!,
              { tool_outputs: toolOutputs, thread_id: thread.data.op.assistantThreadData.threadId! }
            )
            if (!this.shouldProceed(thread)) return
            await this.processAssistantStream.call(this, newStream, agent, model, client, thread, subscriber)
          } catch (error) {
            console.error(`Error processing tool call`, error)
            const errorMessage = error instanceof Error ? error.message : 'Unknown error'
            this.interactor.displayText(`\u26a0\ufe0f Error processing tool call: ${errorMessage}`)
            subscriber.next(
              new ErrorEvent({
                error: new Error(`Error processing OpenAI assistant tool call: ${errorMessage}`),
              })
            )
          }
        }
      }
    } catch (error) {
      this.handleError(error, subscriber, this.aiProviderConfig.name)
    }
  }

  async complete(prompt: string, options?: CompletionOptions): Promise<string> {
    const openai = this.isOpenaiReady()
    if (!openai) throw new Error('OpenAI client not ready')

    // Select model: options > SMALL alias > fallback
    const modelName = options?.model || this.models.find((m) => m.alias === 'SMALL')?.name || 'gpt-4o-mini'

    try {
      const response = await openai.chat.completions.create({
        model: modelName,
        messages: [{ role: 'user', content: prompt }],
        max_completion_tokens: options?.maxTokens ?? 100,
        temperature: options?.temperature ?? 0.5,
        stop: options?.stopSequences,
      })

      return response.choices[0]?.message?.content?.trim() || ''
    } catch (error: any) {
      console.error('OpenAI completion error:', error)
      throw new Error(`OpenAI completion failed: ${error.message}`)
    }
  }
}
