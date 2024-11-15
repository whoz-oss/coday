import {AiClient, CommandContext, DEFAULT_DESCRIPTION, Interactor} from "../model"
import {
  ChatSession,
  EnhancedGenerateContentResponse,
  FunctionDeclaration,
  GoogleGenerativeAI
} from "@google/generative-ai"
import {AiProvider} from "../model/agent-definition"
import {ToolSet} from "../integration/tool-set"
import {Toolbox} from "../integration/toolbox"
import {CodayEvent, ToolRequestEvent} from "../shared/coday-events"
import {AiThread} from "ai-thread/ai-thread"
import {Agent} from "model/agent"
import {Observable} from "rxjs"

export class GeminiClient implements AiClient {
  aiProvider: AiProvider = "GOOGLE"
  multiAssistant = false
  private apiKey: string | undefined
  private genAI: GoogleGenerativeAI | undefined
  threadId: string | null = null
  private chatSession: ChatSession | undefined
  
  private killed: boolean = false
  textAccumulator: string = ""
  private toolbox: Toolbox
  
  constructor(
    private interactor: Interactor,
    private apiKeyProvider: () => string | undefined,
  ) {
    this.apiKey = this.apiKeyProvider()
    if (this.apiKey) {
      this.genAI = new GoogleGenerativeAI(this.apiKey)
    }
    this.toolbox = new Toolbox(interactor)
  }
  
  answer2(agent: Agent, thread: AiThread): Observable<CodayEvent> {
    throw new Error("Method not implemented.")
  }
  
  async isReady(context: CommandContext, toolSet: ToolSet): Promise<boolean> {
    if (!this.apiKey) {
      this.interactor.warn("GEMINI_API_KEY not set, skipping AI command")
      return false
    }
    if (!this.genAI) {
      this.genAI = new GoogleGenerativeAI(this.apiKey)
    }
    if (!this.chatSession) {
      // Get tools through toolbox
      const tools = toolSet.getTools()
      this.chatSession = this.genAI!.getGenerativeModel({
        model: "gemini-1.5-pro",
        tools: [{functionDeclarations: tools.map(tool => tool.function as unknown as FunctionDeclaration)}],
        systemInstruction: `${DEFAULT_DESCRIPTION.description}\n\n${context.project.description}`
      }).startChat({generationConfig: {maxOutputTokens: 10000, temperature: 0.8}})
      
      this.interactor.displayText(`Chat created with ID: ${this.threadId}`)
    }
    return true
  }
  
  async addMessage(
    message: string,
    context: CommandContext
  ): Promise<void> {
    
    // Create fresh ToolSet for this answer with tools from toolbox
    const toolSet = new ToolSet(this.toolbox.getTools(context))
    
    if (!await this.isReady(context, toolSet)) {
      throw new Error("Cannot add message if Gemini client is not set up yet")
    }
    await this.chatSession?.sendMessage(message)
  }
  
  async answer(
    name: string,
    command: string,
    context: CommandContext,
  ): Promise<string> {
    this.textAccumulator = ""
    
    // Create fresh ToolSet for this answer with tools from toolbox
    const toolSet = new ToolSet(this.toolbox.getTools(context))
    
    if (!await this.isReady(context, toolSet)) {
      return "Gemini client not ready"
    }
    
    const response = await this.chatSession!.sendMessageStream(command)
    
    await this.processStream(response.stream, toolSet)
    
    this.interactor.displayText(this.textAccumulator, "Gemini") // TODO handle assistant names
    return this.textAccumulator
  }
  
  private async processStream(
    stream: AsyncGenerator<EnhancedGenerateContentResponse, any, any>,
    toolSet: ToolSet
  ): Promise<void> {
    if (this.killed) {
      return
    }
    
    for await (const chunk of stream) {
      this.interactor.thinking()
      const toolCalls = chunk.functionCalls()
      
      if (toolCalls) {
        try {
          const toolOutputs = await Promise.all(
            toolCalls.map(async call => {
              try {
                const toolRequest = new ToolRequestEvent({
                  toolRequestId: crypto.randomUUID(), // Gemini doesn't provide call IDs
                  name: call.name,
                  args: JSON.stringify(call.args)
                })
                const output = await toolSet.runTool(toolRequest)
                return {
                  functionResponse: {
                    name: call.name,
                    response: {output}
                  }
                }
              } catch (error: any) {
                const errorMessage = error?.message || "Unknown error"
                console.error(`Error executing tool ${call.name}:`, error)
                return {
                  functionResponse: {
                    name: call.name,
                    response: {output: `Error executing tool: ${errorMessage}`}
                  }
                }
              }
            })
          )
          
          const newResponse = await this.chatSession!.sendMessageStream(toolOutputs)
          await this.processStream(newResponse.stream, toolSet)
        } catch (error: any) {
          const errorMessage = error?.message || "Unknown error"
          console.error(`Error processing tool calls:`, error)
          this.interactor.error(`Failed to process tool calls: ${errorMessage}`)
        }
      }
      
      const textIncrement = chunk.text()
      if (textIncrement) {
        this.textAccumulator += textIncrement
      }
    }
  }
  
  reset(): void {
    this.threadId = null
    this.chatSession = undefined
    this.interactor.displayText("Chat has been reset")
  }
  
  kill(): void {
    console.log("Gemini client killed")
    this.killed = true
  }
}