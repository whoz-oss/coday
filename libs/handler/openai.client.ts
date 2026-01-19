import OpenAI from 'openai'
import { Agent, AiClient, AiModel, AiProviderConfig, CompletionOptions, Interactor } from '../model'
import {
  CodayEvent,
  ErrorEvent,
  MessageEvent,
  SummaryEvent,
  TextChunkEvent,
  ToolRequestEvent,
  ToolResponseEvent,
} from '@coday/coday-events'
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
    name: 'gpt-5.1',
    contextWindow: 1000000,
    alias: 'BIG',
    temperature: 0.8,
    maxOutputTokens: 120000,
    price: {
      inputMTokens: 1.25,
      cacheRead: 0.125,
      outputMTokens: 10.0,
    },
  },
  {
    name: 'gpt-5-pro',
    contextWindow: 1000000,
    alias: 'BIGGEST',
    temperature: 0.8,
    maxOutputTokens: 120000,
    price: {
      inputMTokens: 15.0,
      cacheRead: 0, // No cache pricing available
      outputMTokens: 120.0,
    },
  },
  {
    name: 'gpt-5-mini',
    alias: 'SMALL',
    contextWindow: 400000,
    temperature: 1.0,
    maxOutputTokens: 128000,
    price: {
      inputMTokens: 0.25,
      cacheRead: 0.025,
      outputMTokens: 2.0,
    },
  },
  {
    name: 'gpt-5-nano',
    contextWindow: 400000,
    temperature: 1.0,
    maxOutputTokens: 128000,
    price: {
      inputMTokens: 0.05,
      cacheRead: 0.005,
      outputMTokens: 0.4,
    },
  },
]

export class OpenaiClient extends AiClient {
  name: string
  private static readonly MAX_TOOLS = 128

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

  /**
   * Truncates the tools list to OpenAI's maximum of 128 tools and warns the user if truncation occurs
   */
  private truncateToolsIfNeeded(tools: any[]): any[] {
    if (tools.length <= OpenaiClient.MAX_TOOLS) {
      return tools
    }

    this.interactor.warn(
      `⚠️ OpenAI limits tools to ${OpenaiClient.MAX_TOOLS} maximum. Your agent has ${tools.length} tools. ` +
        `Truncating to first ${OpenaiClient.MAX_TOOLS} tools. ` +
        `Consider reducing your integrations or using a shorter tool list for better performance.`
    )

    return tools.slice(0, OpenaiClient.MAX_TOOLS)
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
    const thinking = this.startThinkingInterval()
    this.processThread(openai, agent, model, thread, outputSubject)
      .catch((reason) => {
        outputSubject.next(new ErrorEvent({ error: reason }))
      })
      .finally(() => {
        this.stopThinkingInterval(thinking)
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
    const thinking = this.startThinkingInterval()

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
        this.stopThinkingInterval(thinking)
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
      tools: this.truncateToolsIfNeeded([...agent.tools.getTools(), { type: 'file_search' }]),
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

      // Try streaming first, with fallback to non-streaming
      let response: OpenAI.Chat.Completions.ChatCompletion
      try {
        response = await this.streamApiCall(client, model, agent, thread, data.messages, subscriber)
      } catch (streamError: any) {
        // If streaming fails, fallback to non-streaming
        this.interactor.debug(`⚠️ Streaming failed (${streamError.message}), falling back to non-streaming mode...`)
        response = await this.nonStreamApiCall(client, model, agent, data.messages)
      }

      this.updateUsage(response.usage, agent, model, thread)

      const firstChoice = response.choices[0]!

      if (firstChoice.finish_reason === 'length') throw new Error('Max tokens reached for Openai 😬')

      const text = firstChoice.message.content?.trim()
      this.handleText(thread, text, agent, subscriber)

      const toolRequests = firstChoice.message?.tool_calls
        ?.filter((toolCall) => toolCall.type === 'function')
        .map(
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

  /**
   * Make the API call using streaming for progressive text display
   * @param client OpenAI client
   * @param model AI model configuration
   * @param agent Current agent
   * @param thread AI thread
   * @param messages Thread messages
   * @param subscriber Subject to emit TextChunkEvent for progressive display
   * @returns Complete ChatCompletion response
   */
  private async streamApiCall(
    client: OpenAI,
    model: AiModel,
    agent: Agent,
    thread: AiThread,
    messages: ThreadMessage[],
    subscriber: Subject<CodayEvent>
  ): Promise<OpenAI.Chat.Completions.ChatCompletion> {
    const stream = await client.chat.completions.create({
      model: model.name,
      messages: this.toOpenAiMessage(agent, messages),
      tools: this.truncateToolsIfNeeded(agent.tools.getTools()),
      max_completion_tokens: agent.definition.maxOutputTokens ?? model.maxOutputTokens ?? undefined,
      temperature: agent.definition.temperature ?? model.temperature ?? 0.8,
      stream: true, // Enable streaming
      stream_options: { include_usage: true }, // Include usage data in stream
    })

    // Accumulate response data to reconstruct full completion
    let fullContent = ''
    // Use a more flexible type for accumulating tool calls during streaming
    const toolCalls: Array<{ id: string; type: 'function'; function: { name: string; arguments: string } }> = []
    let finishReason: string | null = null
    let usage: OpenAI.Completions.CompletionUsage | undefined

    // Process stream chunks
    for await (const chunk of stream) {
      // Check for interruption
      if (!this.shouldProceed(thread)) {
        throw new Error('Stream interrupted by user')
      }

      const delta = chunk.choices[0]?.delta

      // Emit text chunks progressively
      if (delta?.content) {
        fullContent += delta.content
        subscriber.next(new TextChunkEvent({ chunk: delta.content }))
      }

      // Accumulate tool calls
      if (delta?.tool_calls) {
        for (const toolCallDelta of delta.tool_calls) {
          const index = toolCallDelta.index
          toolCalls[index] ??= {
            id: toolCallDelta.id || '',
            type: 'function',
            function: { name: '', arguments: '' },
          }
          if (toolCallDelta.id) toolCalls[index].id = toolCallDelta.id
          if (toolCallDelta.function?.name) {
            toolCalls[index].function.name += toolCallDelta.function.name
          }
          if (toolCallDelta.function?.arguments) {
            toolCalls[index].function.arguments += toolCallDelta.function.arguments
          }
        }
      }

      // Capture finish reason
      if (chunk.choices[0]?.finish_reason) {
        finishReason = chunk.choices[0].finish_reason
      }

      // Capture usage if available (usually in last chunk)
      if (chunk.usage) {
        usage = chunk.usage
      }
    }

    // Reconstruct full completion response
    const completion: OpenAI.Chat.Completions.ChatCompletion = {
      id: 'chatcmpl-stream',
      object: 'chat.completion',
      created: Math.floor(Date.now() / 1000),
      model: model.name,
      choices: [
        {
          index: 0,
          message: {
            role: 'assistant',
            content: fullContent || null,
            refusal: null, // Required field for ChatCompletionMessage
            tool_calls: toolCalls.length > 0 ? toolCalls : undefined,
          },
          logprobs: null, // Required field for Choice
          finish_reason: (finishReason as any) || 'stop',
        },
      ],
      usage,
    }

    return completion
  }

  /**
   * Make the API call without streaming (fallback mode)
   * @param client OpenAI client
   * @param model AI model configuration
   * @param agent Current agent
   * @param messages Thread messages
   * @returns Complete ChatCompletion response
   */
  private async nonStreamApiCall(
    client: OpenAI,
    model: AiModel,
    agent: Agent,
    messages: ThreadMessage[]
  ): Promise<OpenAI.Chat.Completions.ChatCompletion> {
    return await client.chat.completions.create({
      model: model.name,
      messages: this.toOpenAiMessage(agent, messages),
      tools: this.truncateToolsIfNeeded(agent.tools.getTools()),
      max_completion_tokens: agent.definition.maxOutputTokens ?? model.maxOutputTokens ?? undefined,
      temperature: agent.definition.temperature ?? model.temperature ?? 0.8,
      stream: false, // Explicitly disable streaming
    })
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

    const openaiMessages = messages.flatMap((msg): ChatCompletionMessageParam[] => {
      // Handle SummaryEvent - just the summary text
      if (msg instanceof SummaryEvent) {
        return [
          {
            role: 'user' as const,
            content: msg.summary,
          },
        ]
      }

      // Handle regular MessageEvent
      if (msg instanceof MessageEvent) {
        const content = msg.content

        // Convert rich content to OpenAI format
        const openaiContent: string | OpenAI.ChatCompletionContentPart[] = content.map((c) => {
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
    // Handle SummaryEvent
    if (m instanceof SummaryEvent) {
      return {
        role: 'user',
        content: m.summary,
      }
    }

    // Handle MessageEvent
    if (m instanceof MessageEvent) {
      // For assistant API, convert rich content to text representation
      const content = m.content
        .filter((c) => c.type === 'text')
        .map((c) => c.content)
        .join('\n')

      return {
        role: m.role,
        content,
      }
    }

    // Handle ToolResponseEvent
    if (m instanceof ToolResponseEvent) {
      return {
        role: 'user',
        content: `Here is the result of : \n<toolRequestId>${m.toolRequestId}</toolRequestId>\n<output>${m.output}</output>`,
      }
    }

    // Handle ToolRequestEvent
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
              chunk.data.required_action?.submit_tool_outputs.tool_calls
                ?.filter((toolCall) => toolCall.type === 'function')
                .map(
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
                  const errorMessage = `Error running tool ${request.name}: ${error}`
                  console.error(errorMessage)
                  responseEvent = request.buildResponse(errorMessage)
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
        temperature: 1.0,
      })

      return response.choices[0]?.message?.content?.trim() || ''
    } catch (error: any) {
      console.error('OpenAI completion error:', error)
      throw new Error(`OpenAI completion failed: ${error.message}`)
    }
  }
}
