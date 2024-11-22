import {Agent, AiClient, Interactor, ModelSize} from "../model"
import Anthropic from "@anthropic-ai/sdk"
import {MessageParam} from "@anthropic-ai/sdk/resources"
import {ToolSet} from "../integration/tool-set"
import {CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {Observable, of, Subject} from "rxjs"
import {AiThread} from "../ai-thread/ai-thread"
import {ThreadMessage} from "../ai-thread/ai-thread.types"
import {TextBlockParam} from "@anthropic-ai/sdk/resources/messages"

const AnthropicModels = {
  [ModelSize.BIG]: {
    model: "claude-3-5-sonnet-latest",
    contextWindow: 200000,
    price: {
      inputMTokens: 3,
      cacheWrite: 3.75,
      cacheRead: 0.3,
      outputMTokens: 15
    }
  },
  [ModelSize.SMALL]: {
    model: "claude-3-haiku-20240307",
    contextWindow: 200000,
    price: {
      inputMTokens: 1,
      cacheWrite: 1.25,
      cacheRead: 0.1,
      outputMTokens: 5
    }
  }
}

export class AnthropicClient extends AiClient {
  
  constructor(
    readonly interactor: Interactor,
    private readonly apiKeyProvider: () => string | undefined,
  ) {
    super()
  }
  
  async run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    const anthropic: Anthropic | undefined = this.isAnthropicReady()
    if (!anthropic) return of()
    
    thread.data.anthropic = {
      price: 0
    }
    const outputSubject: Subject<CodayEvent> = new Subject()
    this.processThread(anthropic, agent, thread, outputSubject).finally(() => outputSubject.complete())
    return outputSubject
  }
  
  private async processThread(
    client: Anthropic,
    agent: Agent,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    const thinking = setInterval(() => this.interactor.thinking(), 3000)
    try {
      const response = await client.messages.create({
        model: AnthropicModels[this.getModelSize(agent)].model,
        messages: this.toClaudeMessage(thread.getMessages()),
        system: [{
          text: agent.systemInstructions,
          type: "text",
          cache_control: {type: "ephemeral"}
        }] as unknown as Array<TextBlockParam>,
        tools: this.getClaudeTools(agent.tools),
        temperature: agent.definition.temperature ?? 0.8,
        max_tokens: 8192
      })
      
      clearInterval(thinking)
      
      thread.data.anthropic.price += this.computePrice(response?.usage, agent)
      
      if (response.stop_reason === "max_tokens") throw new Error("Max tokens reached for Anthropic 😬")
      
      const text = response.content
        .filter(block => block.type === "text")
        .map(block => block.text.trim())
        .filter(t => !!t)
        .join("\n")
      this.handleText(thread, text, agent, subscriber)
      
      const toolRequests = response.content
        .filter(block => block.type === "tool_use")
        .map(block => new ToolRequestEvent({
          toolRequestId: block.id,
          name: block.name,
          args: JSON.stringify(block.input)
        }))
      
      if (await this.shouldProcessAgainAfterResponse(text, toolRequests, agent, thread)) {
        // then tool responses to send
        await this.processThread(client, agent, thread, subscriber)
      } else {
        // end of run, show the bill
        this.showPrice(thread.data.anthropic.price)
      }
      
    } catch (error: any) {
      clearInterval(thinking)
      this.showPrice(thread.data.anthropic.price)
      subscriber.next(new ErrorEvent({
        error
      }))
    }
  }
  
  private computePrice(usage: any, agent: Agent): number {
    const input = usage?.input_tokens * AnthropicModels[this.getModelSize(agent)].price.inputMTokens
    const output = usage?.output_tokens * AnthropicModels[this.getModelSize(agent)].price.outputMTokens
    const cacheWrite = usage?.cache_creation_input_tokens * AnthropicModels[this.getModelSize(agent)].price.cacheWrite
    const cacheRead = usage?.cache_read_input_tokens * AnthropicModels[this.getModelSize(agent)].price.cacheRead
    return (input + output + cacheWrite + cacheRead) / 1_000_000
  }
  
  
  private isAnthropicReady(): Anthropic | undefined {
    const apiKey: string | undefined = this.apiKeyProvider()
    if (!apiKey) {
      this.interactor.warn(
        "ANTHROPIC_API_KEY not set, skipping AI command",
      )
      return
    }
    
    return new Anthropic({
      apiKey,
      /**
       * Special beta header to enable prompt caching
       */
      defaultHeaders: {["anthropic-beta"]: "prompt-caching-2024-07-31"}
    })
  }
  
  /**
   * Convert a ThreadMessage to Claude's MessageParam format
   */
  private toClaudeMessage(messages: ThreadMessage[]): MessageParam[] {
    return messages.map(msg => {
      let claudeMessage: MessageParam | undefined
      if (msg instanceof MessageEvent) {
        claudeMessage = {role: msg.role, content: msg.content}
      }
      if (msg instanceof ToolRequestEvent) {
        claudeMessage = {
          role: "assistant",
          content: [{
            type: "tool_use",
            id: msg.toolRequestId,
            name: msg.name,
            input: JSON.parse(msg.args)
          }]
        }
      }
      if (msg instanceof ToolResponseEvent) {
        claudeMessage = {
          role: "user",
          content: [{
            type: "tool_result",
            tool_use_id: msg.toolRequestId,
            content: msg.output
          }]
        }
      }
      return claudeMessage
    }).filter(m => !!m)
  }
  
  
  /**
   * Map tool definitions to match Anthropic's API
   *
   * @param toolSet
   * @private
   */
  private getClaudeTools(toolSet: ToolSet) {
    return toolSet.getTools().map(t => ({
        name: t.function.name,
        description: t.function.description,
        input_schema: t.function.parameters
      })
    )
  }
}