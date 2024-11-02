import {AiClient, AssistantDescription, CommandContext, DEFAULT_DESCRIPTION, Interactor} from "../model"
import Anthropic from "@anthropic-ai/sdk"
import {MessageParam} from "@anthropic-ai/sdk/resources"
import {Toolbox} from "../integration/toolbox"
import {ToolCall} from "../integration/tool-call"
import {ToolSet} from "../integration/tool-set"
import {ToolRequestEvent} from "../shared/coday-events"
import {AiProvider, ModelSize} from "../model/agent-definition"

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

export class ClaudeClient implements AiClient {
  aiProvider: AiProvider = "ANTHROPIC"
  multiAssistant = true
  private apiKey: string | undefined
  private textAccumulator: string = ""
  private killed: boolean = false
  private client: Anthropic | undefined
  
  toolBox: Toolbox
  
  constructor(
    private readonly interactor: Interactor,
    private readonly apiKeyProvider: () => string | undefined,
  ) {
    this.toolBox = new Toolbox(interactor)
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
      const messages: MessageParam[] = []
      context.data.claudeData = {
        messages
      }
    }
    // Message accumulator equivalent to thread
    const messages: MessageParam[] = context.data.claudeData.messages
    
    // Create new toolSet instance with current context tools
    const toolSet = new ToolSet(this.toolBox.getTools(context))
    
    // add command to history
    messages.push({role: "user", content: command})
    
    await this.processMessages(messages, DEFAULT_DESCRIPTION, toolSet, context)
    
    return this.textAccumulator
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
  
  async processMessages(
    messages: MessageParam[], // TODO: replace later by AiThread
    assistant: AssistantDescription, // TODO: replace by AgentDefinition
    toolSet: ToolSet,
    context: CommandContext // TODO: integrate relevant infos into AiThread ? Used just for project.description ?
  ): Promise<void> {
    if (this.killed) {
      return
    }
    
    const thinking = setInterval(() => this.interactor.thinking(), 3000)
    
    // define system instructions for the model (to be done each call)
    const system = `${assistant.systemInstructions}\n\n
        <project-context>
${context.project.description}
</project-context>`
    
    try {
      // TODO: trigger messages summarization if needed just before calling
      
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
        messages.push({role: "assistant", content: text})
      }
      
      const toolUseBlocks = response?.content.filter(block => block.type === "tool_use")
      if (toolUseBlocks?.length) {
        messages.push({role: "assistant", content: toolUseBlocks})
      }
      const toolUses: ToolCall[] = toolUseBlocks.map(block => ({
        id: block.id,
        name: block.name,
        args: JSON.stringify(block.input) // TODO: avoid stringification because just parsed after...
      }))
      
      const toolOutputs = await Promise.all(
        toolUses.map(async (call: ToolCall) => {
          const toolRequest = new ToolRequestEvent(call)
          const output = await toolSet.runTool(toolRequest)
          return {
            type: "tool_result" as const,
            tool_use_id: call.id!,
            content: output
          }
        })
      )
      
      const toolBlock: MessageParam = {
        role: "user",
        content: toolOutputs
      }
      
      messages.push(toolBlock)
      
      if (toolOutputs.length) {
        await this.processMessages(messages, assistant, toolSet, context)
      }
      
    } catch (error) {
      console.error(error)
      throw new Error(`Error calling Claude API: ${(error as Error).message}`)
    }
  }
  
  reset(): void {
    this.interactor.displayText("Conversation has been reset")
  }
  
  kill(): void {
    this.killed = true
  }
}