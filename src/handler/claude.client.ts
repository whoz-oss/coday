import {AiClient, AssistantDescription, CommandContext, DEFAULT_DESCRIPTION, Interactor} from "../model"
import Anthropic from "@anthropic-ai/sdk"
import {MessageParam, Tool, ToolResultBlockParam} from "@anthropic-ai/sdk/resources"
import {Toolbox} from "../integration/toolbox"
import {ToolCall} from "../integration/tool-call"
import {ToolRequestEvent, ToolResponseEvent} from "../shared/coday-events"
import {filter, first, firstValueFrom, map, Observable} from "rxjs"
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
  
  async isReady(assistantName: string, context: CommandContext): Promise<boolean> {
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
    this.interactor.error("NOT IMPLEMENTED YET !!!")
  }
  
  async answer(name: string, command: string, context: CommandContext): Promise<string> {
    this.textAccumulator = ""
    if (!(await this.isReady(name, context))) {
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
    const messages: MessageParam[] = context.data.claudeData.messages
    
    
    // add command to history
    messages.push({role: "user", content: command})
    
    // map tool definitions to match Anthropic's API
    const tools: Tool[] = this.toolBox.getTools(context).map(t => ({
        name: t.function.name,
        description: t.function.description,
        input_schema: t.function.parameters
      })
    )
    
    // TODO: re-enable multi-assistant with a more stable conversation mechanism
    // const lowerCaseName = name.toLowerCase()
    // const assistant = context.project.assistants?.find(a => a.name.toLowerCase().startsWith(lowerCaseName)) ?? DEFAULT_DESCRIPTION
    await this.processMessages(messages, DEFAULT_DESCRIPTION, tools, context)
    
    return this.textAccumulator
  }
  
  async processMessages(
    messages: MessageParam[],
    assistant: AssistantDescription,
    tools: Tool[],
    context: CommandContext
  ): Promise<void> {
    if (this.killed) {
      return
    }
    
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
        tools,
        max_tokens: 8192, // max powaaaa, let's ai-roast the planet !!!
      })
      
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
        args: JSON.stringify(block.input)
      }))
      
      const toolOutputs = await Promise.all(
        toolUses.map(async (call) => {
          const toolRequest = new ToolRequestEvent(call)
          const toolResponse: Observable<ToolResultBlockParam> = this.interactor.events.pipe(
            filter(event => event instanceof ToolResponseEvent),
            filter(response => response.toolRequestId === toolRequest.toolRequestId),
            first(),
            map(response => ({type: "tool_result", tool_use_id: call.id!, content: response.output})),
          )
          this.interactor.sendEvent(toolRequest)
          return firstValueFrom(toolResponse)
        })
      )
      
      const toolBlock: MessageParam = {
        role: "user",
        content: toolOutputs
      }
      
      messages.push(toolBlock)
      
      if (toolOutputs.length) {
        await this.processMessages(messages, assistant, tools, context)
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
