import { Agent, AiClient, Interactor, ModelSize } from '../model'
import Anthropic from '@anthropic-ai/sdk'
import { MessageParam } from '@anthropic-ai/sdk/resources'
import { ToolSet } from '../integration/tool-set'
import { CodayEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent } from '@coday/coday-events'
import { Observable, of, Subject } from 'rxjs'
import { AiThread } from '../ai-thread/ai-thread'
import { ThreadMessage } from '../ai-thread/ai-thread.types'
import { TextBlockParam } from '@anthropic-ai/sdk/resources/messages'

const AnthropicModels = {
  [ModelSize.BIG]: {
    name: 'claude-sonnet-4-20250514',
    contextWindow: 200000,
    price: {
      inputMTokens: 3,
      cacheWrite: 3.75,
      cacheRead: 0.3,
      outputMTokens: 15,
    },
  },
  [ModelSize.SMALL]: {
    name: 'claude-3-5-haiku-latest',
    contextWindow: 200000,
    price: {
      inputMTokens: 0.8,
      cacheWrite: 1,
      cacheRead: 0.08,
      outputMTokens: 4,
    },
  },
}

export class AnthropicClient extends AiClient {
  name: string

  constructor(
    readonly interactor: Interactor,
    private readonly apiKey: string | undefined
  ) {
    super()
    this.name = 'Anthropic'
  }

  async run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    const anthropic: Anthropic | undefined = this.isAnthropicReady()
    if (!anthropic) return of()

    thread.resetUsageForRun()
    const outputSubject: Subject<CodayEvent> = new Subject()
    const thinking = setInterval(() => this.interactor.thinking(), this.thinkingInterval)
    this.processThread(anthropic, agent, thread, outputSubject).finally(() => {
      clearInterval(thinking)
      this.showAgentAndUsage(agent, 'Anthropic', AnthropicModels[this.getModelSize(agent)].name, thread)
      outputSubject.complete()
    })
    return outputSubject
  }

  private async processThread(
    client: Anthropic,
    agent: Agent,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    try {
      const initialContextCharLength = agent.systemInstructions.length + agent.tools.charLength + 20
      const model = AnthropicModels[this.getModelSize(agent)]
      const charBudget = model.contextWindow * this.charsPerToken - initialContextCharLength

      const response = await client.messages.create({
        model: model.name,
        messages: this.toClaudeMessage(thread.getMessages(charBudget)),
        system: [
          {
            text: agent.systemInstructions,
            type: 'text',
            cache_control: { type: 'ephemeral' },
          },
        ] as unknown as Array<TextBlockParam>,
        tools: this.getClaudeTools(agent.tools),
        temperature: agent.definition.temperature ?? 0.8,
        max_tokens: 8192,
      })

      this.updateUsage(response?.usage, agent, thread)

      if (response.stop_reason === 'max_tokens') throw new Error('Max tokens reached for Anthropic 😬')

      const text = response.content
        .filter((block) => block.type === 'text')
        .map((block) => block.text.trim())
        .filter((t) => !!t)
        .join('\n')
      this.handleText(thread, text, agent, subscriber)

      const toolRequests = response.content
        .filter((block) => block.type === 'tool_use')
        .map(
          (block) =>
            new ToolRequestEvent({
              toolRequestId: block.id,
              name: block.name,
              args: JSON.stringify(block.input),
            })
        )
      if (await this.shouldProcessAgainAfterResponse(text, toolRequests, agent, thread)) {
        // then tool responses to send
        await this.processThread(client, agent, thread, subscriber)
      }
    } catch (error: any) {
      this.handleError(error, subscriber, this.name)
    }
  }

  private updateUsage(usage: any, agent: Agent, thread: AiThread): void {
    const input = usage?.input_tokens * AnthropicModels[this.getModelSize(agent)].price.inputMTokens
    const output = usage?.output_tokens * AnthropicModels[this.getModelSize(agent)].price.outputMTokens
    const cacheWrite = usage?.cache_creation_input_tokens * AnthropicModels[this.getModelSize(agent)].price.cacheWrite
    const cacheRead = usage?.cache_read_input_tokens * AnthropicModels[this.getModelSize(agent)].price.cacheRead
    const price = (input + output + cacheWrite + cacheRead) / 1_000_000

    thread.addUsage({
      input: usage?.input_tokens ?? 0,
      output: usage?.output_tokens ?? 0,
      cache_read: usage?.cache_read_input_tokens ?? 0,
      cache_write: usage?.cache_creation_input_tokens ?? 0,
      price,
    })
  }

  private isAnthropicReady(): Anthropic | undefined {
    if (!this.apiKey) {
      this.interactor.warn('ANTHROPIC_API_KEY not set, skipping AI command. Please configure your API key.')
      return
    }

    try {
      return new Anthropic({
        apiKey: this.apiKey,
        /**
         * Special beta header to enable prompt caching
         */
        defaultHeaders: { ['anthropic-beta']: 'prompt-caching-2024-07-31' },
      })
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Unknown error'
      this.interactor.warn(`Failed to initialize Anthropic client: ${errorMessage}`)
      console.error('Anthropic client initialization error:', error)
      return
    }
  }

  /**
   * Convert a ThreadMessage to Claude's MessageParam format
   */
  private toClaudeMessage(messages: ThreadMessage[]): MessageParam[] {
    return messages
      .map((msg) => {
        let claudeMessage: MessageParam | undefined
        if (msg instanceof MessageEvent) {
          claudeMessage = { role: msg.role, content: msg.content }
        }
        if (msg instanceof ToolRequestEvent) {
          claudeMessage = {
            role: 'assistant',
            content: [
              {
                type: 'tool_use',
                id: msg.toolRequestId,
                name: msg.name,
                input: JSON.parse(msg.args),
              },
            ],
          }
        }
        if (msg instanceof ToolResponseEvent) {
          claudeMessage = {
            role: 'user',
            content: [
              {
                type: 'tool_result',
                tool_use_id: msg.toolRequestId,
                content: msg.output,
              },
            ],
          }
        }
        return claudeMessage
      })
      .filter((m) => !!m)
  }

  /**
   * Map tool definitions to match Anthropic's API
   *
   * @param toolSet
   * @private
   */
  private getClaudeTools(toolSet: ToolSet) {
    return toolSet.getTools().map((t) => ({
      name: t.function.name,
      description: t.function.description,
      input_schema: t.function.parameters,
    }))
  }
}
