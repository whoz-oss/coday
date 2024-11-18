import {
  Agent,
  AiClient,
  AiProvider,
  AssistantDescription,
  CommandContext,
  DEFAULT_DESCRIPTION,
  Interactor,
  ModelSize
} from "../model"
import Anthropic from "@anthropic-ai/sdk"
import {MessageParam} from "@anthropic-ai/sdk/resources"
import {Toolbox} from "../integration/toolbox"
import {ToolCall, ToolResponse} from "../integration/tool-call"
import {ToolSet} from "../integration/tool-set"
import {CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {Observable, of, Subject} from "rxjs"
import {AiThread} from "../ai-thread/ai-thread"
import {RunStatus, ThreadMessage} from "../ai-thread/ai-thread.types"

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
  aiProvider: AiProvider = "ANTHROPIC"
  multiAssistant = true
  private apiKey: string | undefined
  private textAccumulator: string = ""
  private killed: boolean = false
  
  private client: Anthropic | undefined
  
  
  async answer2(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
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
        max_tokens: 8192
      })
      
      clearInterval(thinking)
      
      // Emit text content if any
      const text = response.content
        .filter(block => block.type === "text")
        .map(block => block.text)
        .join("\n")
      
      if (text) {
        thread.addAgentMessage(agent.name, text)
        subscriber.next(new MessageEvent({
          role: "assistant",
          content: text,
          name: agent.name
        }))
      }
      
      // Process and emit tool calls
      const toolRequests = response.content
        .filter(block => block.type === "tool_use")
        .map(block => new ToolRequestEvent({
          toolRequestId: block.id,
          name: block.name,
          args: JSON.stringify(block.input)
        }))
      if (toolRequests.length > 0) {
        // Emit requests and process responses in parallel
        await Promise.all(toolRequests.map(async request => {
          let responseEvent: ToolResponseEvent
          try {
            responseEvent = await agent.tools.run(request)
          } catch (error: any) {
            console.error(`Error running tool ${request.name}:`, error)
            responseEvent = new ToolResponseEvent({
              toolRequestId: request.toolRequestId,
              output: `Error: ${error.message}`
            })
          }
          thread.addToolRequests(agent.name, [request])
          thread.addToolResponseEvents([responseEvent])
        }))
        
        // Check interruption before recursion
        if (this.mustStop(thread)) return
        
        // Continue with tool responses
        await this.processResponse(client, agent, thread, subscriber)
      }
      
    } catch (error: any) {
      clearInterval(thinking)
      subscriber.next(new ErrorEvent({
        error
      }))
    }
  }
  
  toolBox: Toolbox
  
  constructor(
    private readonly interactor: Interactor,
    private readonly apiKeyProvider: () => string | undefined,
  ) {
    super()
    this.toolBox = new Toolbox(interactor)
  }
  
  kill(): void {
    this.killed = true
  }
  
  private mustStop(thread: AiThread): boolean {
    return thread.runStatus === RunStatus.STOPPED || this.killed
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
  
  async addMessage(message: string, context: CommandContext): Promise<void> {
    // Implement add message logic if applicable
    this.interactor.error("NOT IMPLEMENTED !!!")
    
    /*
    addMessage should be done at AiThread level instead of inside the client.
    Client should only process a command with a given thread, not manage data
     */
  }
  
  async answer(name: string, command: string, context: CommandContext): Promise<string> {
    this.textAccumulator = ""
    if (!(await this.isReady())) {
      return "Claude client not ready"
    }
    
    if (!this.client) {
      throw new Error("Anthropic client not initialized.")
    }
    
    // init the thread data
    if (!context.data.claudeData) {
      context.data.claudeData = {
        thread: new AiThread({id: `thread_${new Date().toISOString()}`, messages: []})
      }
    }
    // Message accumulator equivalent to thread
    const thread = context.data.claudeData.thread
    
    // Create new toolSet instance with current context tools
    const toolSet = new ToolSet(this.toolBox.getTools(context))
    
    // add command to history
    thread.addUserMessage("user", command)
    
    await this.processMessages(thread, DEFAULT_DESCRIPTION, toolSet, context)
    
    return this.textAccumulator
  }
  
  async processMessages(
    aiThread: AiThread,
    assistant: AssistantDescription, // TODO: replace by AgentDefinition
    toolSet: ToolSet,
    context: CommandContext, // TODO: integrate relevant infos into AiThread ? Used just for project.description ?
  ): Promise<void> {
    const thinking = setInterval(() => this.interactor.thinking(), 3000)
    
    // define system instructions for the model (to be done each call)
    const system = `${assistant.systemInstructions}\n\n
        <project-context>
${context.project.description}
</project-context>`
    
    try {
      const messages = this.toClaudeMessage(aiThread.getMessages())
      const response = await this.client!.messages.create({
        model: ClaudeModels[ModelSize.BIG].model,
        messages,
        system: system,
        tools: this.getClaudeTools(toolSet),
        max_tokens: 8192, // max powaaaa, let's ai-roast the planet !!!
      })
      
      clearInterval(thinking)
      
      const text = response?.content?.filter(block => block.type === "text").map(block => block.text).join("\n")
      this.textAccumulator += text
      this.interactor.displayText(text, assistant.name)
      
      // push a compacted version of the assistant text response
      if (text) {
        aiThread.addAgentMessage(assistant.name, text)
      }
      
      const toolUseBlocks = response?.content.filter(block => block.type === "tool_use")
      const toolUses: ToolCall[] = toolUseBlocks.map(block => ({
        id: block.id,
        name: block.name,
        args: JSON.stringify(block.input) // TODO: avoid stringification because just parsed after...
      }))
      
      const toolResponses: ToolResponse[] = await Promise.all(
        toolUses.map(async (call: ToolCall): Promise<ToolResponse> => {
          const toolRequest = new ToolRequestEvent(call)
          const response = await toolSet.runTool(toolRequest)
          return {
            id: call.id!,
            name: call.name,
            response
          }
        })
      )
      
      if (toolUses?.length && toolResponses.length === toolUses.length) {
        aiThread.addToolCalls(assistant.name, toolUses)
        aiThread.addToolResponses("user", toolResponses)
      }
      
      if (toolResponses.length && aiThread.runStatus === RunStatus.RUNNING) {
        await this.processMessages(aiThread, assistant, toolSet, context)
      }
      
    } catch (error: any) {
      console.error(error)
      throw new Error(`Error calling Claude API: ${error.message}`)
    }
  }
  
  reset(): void {
    this.interactor.displayText("Conversation has been reset")
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