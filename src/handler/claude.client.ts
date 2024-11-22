import {Agent, AiClient, Interactor, ModelSize} from "../model"
import Anthropic from "@anthropic-ai/sdk"
import {MessageParam} from "@anthropic-ai/sdk/resources"
import {ToolSet} from "../integration/tool-set"
import {CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {Observable, of, Subject} from "rxjs"
import {AiThread} from "../ai-thread/ai-thread"
import {ThreadMessage} from "../ai-thread/ai-thread.types"

const ClaudeModels = {
  [ModelSize.BIG]: {
    model: "claude-3-5-sonnet-latest",
    contextWindow: 200000
  },
  [ModelSize.SMALL]: {
    model: "claude-3-haiku-20240307",
    contextWindow: 200000
  }
}

export class ClaudeClient extends AiClient {
  multiAssistant = true
  private apiKey: string | undefined
  
  private client: Anthropic | undefined
  
  constructor(
    private readonly interactor: Interactor,
    private readonly apiKeyProvider: () => string | undefined,
  ) {
    super()
  }
  
  async run(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    if (!(await this.isReady())) return of()
    
    const outputSubject: Subject<CodayEvent> = new Subject()
    this.processResponse(this.client!, agent, thread, outputSubject).finally(() => outputSubject.complete())
    return outputSubject
  }
  
  private async processResponse(
    client: Anthropic,
    agent: Agent,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    const thinking = setInterval(() => this.interactor.thinking(), 3000)
    try {
      const response = await client.messages.create({
        model: ClaudeModels[agent.definition.modelSize ?? ModelSize.BIG].model,
        messages: this.toClaudeMessage(thread.getMessages()),
        system: agent.systemInstructions,
        tools: this.getClaudeTools(agent.tools),
        temperature: agent.definition.temperature ?? 0.8,
        max_tokens: 8192
      })
      
      clearInterval(thinking)
      
      // Emit text content if any
      const text = response.content
        .filter(block => block.type === "text")
        .map(block => block.text.trim())
        .filter(t => !!t)
        .join("\n")
      
      
      // Process and emit tool calls
      const toolRequests = response.content
        .filter(block => block.type === "tool_use")
        .map(block => new ToolRequestEvent({
          toolRequestId: block.id,
          name: block.name,
          args: JSON.stringify(block.input)
        }))
      
      
      if (await this.shouldProcessAgainAfterResponse(text, toolRequests, agent, thread, subscriber)) {
        await this.processResponse(client, agent, thread, subscriber)
      }
      
    } catch (error: any) {
      clearInterval(thinking)
      subscriber.next(new ErrorEvent({
        error
      }))
    }
  }
  
  
  kill(): void {
    this.killed = true
  }
  
  async isReady(): Promise<boolean> {
    this.apiKey = this.apiKeyProvider()
    if (this.apiKey) {
      this.client = new Anthropic({
        apiKey: this.apiKey
      })
    }
    if (!this.apiKey) {
      this.interactor.warn("ANTHROPIC_CLAUDE_API_KEY not set, skipping AI command")
      return false
    }
    return true
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