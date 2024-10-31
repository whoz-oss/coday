import {AiClient, CommandContext, DEFAULT_DESCRIPTION, Interactor} from "../model"
import {
  ChatSession,
  EnhancedGenerateContentResponse,
  FunctionDeclaration,
  GoogleGenerativeAI
} from "@google/generative-ai"
import {Toolbox} from "../integration/toolbox"
import {ToolCall} from "../integration/tool-call"
import {ToolRequestEvent, ToolResponseEvent} from "../shared" // TODO: fix ts config ???
import {filter, firstValueFrom, map, take} from "rxjs"
import {CodayTool} from "../integration/assistant-tool-factory"
import {AiProvider} from "../model/agent-definition"

export class GeminiClient implements AiClient {
  aiProvider: AiProvider = "GOOGLE"
  multiAssistant = false
  private apiKey: string | undefined
  private genAI: GoogleGenerativeAI | undefined
  threadId: string | null = null
  private chatSession: ChatSession | undefined
  
  toolBox: Toolbox
  tools: CodayTool[] = []
  private killed: boolean = false
  textAccumulator: string = ""
  
  constructor(
    private interactor: Interactor,
    private apiKeyProvider: () => string | undefined,
  ) {
    this.apiKey = this.apiKeyProvider()
    if (this.apiKey) {
      this.genAI = new GoogleGenerativeAI(this.apiKey)
    }
    this.toolBox = new Toolbox(interactor)
  }
  
  async isReady(context: CommandContext): Promise<boolean> {
    if (!this.apiKey) {
      this.interactor.warn("GEMINI_API_KEY not set, skipping AI command")
      return false
    }
    if (!this.genAI) {
      this.genAI = new GoogleGenerativeAI(this.apiKey)
    }
    if (!this.chatSession) {
      this.tools = this.toolBox.getTools(context)
      this.chatSession = this.genAI!.getGenerativeModel({
        model: "gemini-1.5-pro",
        tools: [{functionDeclarations: this.tools.map(t => t.function as unknown as FunctionDeclaration)}],
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
    if (!await this.isReady(context)) {
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
    if (!await this.isReady(context)) {
      return "Gemini client not ready"
    }
    const response = await this.chatSession!.sendMessageStream(command)
    
    await this.processStream(response.stream, this.tools)
    
    this.interactor.displayText(this.textAccumulator, "Gemini") // TODO handle assistant names
    return this.textAccumulator
  }
  
  private async processStream(stream: AsyncGenerator<EnhancedGenerateContentResponse, any, any>, tools: CodayTool[]): Promise<void> {
    if (this.killed) {
      return
    }
    
    for await (const chunk of stream) {
      this.interactor.thinking()
      const toolCalls = chunk.functionCalls()
      
      const toolOutputs = toolCalls ? await Promise.all(
        toolCalls?.map(async call => {
          const toolCall: ToolCall = {
            name: call.name,
            args: JSON.stringify(call.args) // TODO: keep args as object (ie parse sooner in openai.client.ts)
          }
          
          const toolRequest = new ToolRequestEvent(toolCall)
          const toolResponse = this.interactor.events.pipe(
            filter(event => event instanceof ToolResponseEvent),
            filter(response => response.toolRequestId === toolRequest.toolRequestId),
            take(1),
            map(response => ({functionResponse: {name: call.name, response: {output: response.output}}}))
          )
          this.interactor.sendEvent(toolRequest)
          return firstValueFrom(toolResponse)
        })
      ) : undefined
      
      if (toolOutputs) {
        const newResponse = await this.chatSession!.sendMessageStream(toolOutputs)
        await this.processStream(newResponse.stream, tools)
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