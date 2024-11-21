import OpenAI from "openai"
import {AssistantStream} from "openai/lib/AssistantStream"
import {
  AiClient,
  AiProvider,
  AssistantDescription,
  CommandContext,
  DEFAULT_DESCRIPTION,
  Interactor,
  ModelSize
} from "../model"
import {Toolbox} from "../integration/toolbox"
import {ToolCall} from "../integration/tool-call"
import {CodayEvent, ErrorEvent, MessageEvent, ToolRequestEvent, ToolResponseEvent} from "../shared"
import {ToolSet} from "../integration/tool-set"
import {Assistant} from "openai/resources/beta"
import {AiThread} from "ai-thread/ai-thread"
import {Agent} from "model/agent"
import {Observable, of, Subject} from "rxjs"
import {ThreadMessage} from "../ai-thread/ai-thread.types"
import {ChatCompletionMessageParam, ChatCompletionSystemMessageParam} from "openai/resources/chat/completions"

const DEFAULT_MODEL: string = "gpt-4o"
const DEFAULT_TEMPERATURE: number = 0.75

const OpenaiModels = {
  [ModelSize.BIG]: {
    model: "gpt-4o",
    contextWindow: 128000
  },
  [ModelSize.SMALL]: {
    model: "gpt-4o-mini",
    contextWindow: 128000
  },
}

type AssistantReference = { name: string; id: string }

export class OpenaiClient extends AiClient {
  aiProvider: AiProvider = "OPENAI"
  multiAssistant = true
  openai: OpenAI | undefined
  textAccumulator: string = ""
  
  toolBox: Toolbox
  apiKey: string | undefined
  assistants: AssistantReference[] = []
  assistant: AssistantReference | undefined
  
  constructor(
    private interactor: Interactor,
    private apiKeyProvider: () => string | undefined,
    private apiUrl: string | undefined = undefined
  ) {
    super()
    this.toolBox = new Toolbox(interactor)
  }
  
  async answer2(agent: Agent, thread: AiThread): Promise<Observable<CodayEvent>> {
    if (!(this.isOpenaiReady())) return of()
    
    const outputSubject: Subject<CodayEvent> = new Subject()
    this.processResponse(this.openai!, agent, thread, outputSubject).finally(() => outputSubject.complete())
    return outputSubject
  }
  
  private async processResponse(
    client: OpenAI,
    agent: Agent,
    thread: AiThread,
    subscriber: Subject<CodayEvent>
  ): Promise<void> {
    const thinking = setInterval(() => this.interactor.thinking(), 3000)
    try {
      const response = await client.chat.completions.create({
        model: OpenaiModels[agent.definition.modelSize ?? ModelSize.BIG].model,
        messages: this.toOpenAiMessage(agent, thread.getMessages()),
        tools: agent.tools.getTools(),
        max_completion_tokens: undefined,
        temperature: agent.definition.temperature ?? 0.8
      })
      
      clearInterval(thinking)
      
      const text = response.choices[0].message.content?.trim()
      
      const toolRequests = response.choices[0].message?.tool_calls
        ?.map(toolCall => new ToolRequestEvent({
          toolRequestId: toolCall.id,
          name: toolCall.function.name,
          args: toolCall.function.arguments
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
  
  private toOpenAiMessage(agent: Agent, messages: ThreadMessage[]): ChatCompletionMessageParam[] {
    const systemInstructionMessage: ChatCompletionSystemMessageParam = {
      content: agent.systemInstructions,
      role: "system"
    }
    const openaiMessages = messages.map(msg => {
      let openaiMessage: ChatCompletionMessageParam | undefined
      if (msg instanceof MessageEvent) {
        const role = msg.role === "assistant" ? "system" : "user"
        openaiMessage = {
          role,
          content: msg.content,
          name: msg.role === "user" ? msg.name : undefined
        }
      }
      if (msg instanceof ToolRequestEvent) {
        openaiMessage = {
          role: "assistant",
          name: agent.name,
          tool_calls: [{
            type: "function",
            id: msg.toolRequestId,
            function: {
              name: msg.name,
              arguments: msg.args
            }
          }]
        }
      }
      if (msg instanceof ToolResponseEvent) {
        openaiMessage = {
          role: "tool",
          content: msg.output,
          tool_call_id: msg.toolRequestId
        }
      }
      return openaiMessage
    }).filter(m => !!m)
    
    return [systemInstructionMessage, ...openaiMessages]
  }
  
  async isReady(
    assistantName: string,
    context: CommandContext,
  ): Promise<boolean> {
    if (!this.isOpenaiReady()) {
      return false
    }
    
    this.assistant = await this.findAssistant(assistantName, context)
    
    await this.createThread(context)
    
    return true
  }
  
  async addMessage(
    message: string,
    context: CommandContext
  ): Promise<void> {
    if (!this.isOpenaiReady()) {
      throw new Error("Cannot add message if openai not setup yet")
    }
    await this.createThread(context)
    
    const threadId = context.data.openaiData.threadId
    
    await this.openai!.beta.threads.messages.create(threadId, {
      role: "user",
      content: message,
    })
  }
  
  async answer(
    name: string,
    command: string,
    context: CommandContext,
  ): Promise<string> {
    const assistantName = name.toLowerCase()
    
    this.textAccumulator = ""
    if (!(await this.isReady(assistantName, context))) {
      return "Openai client not ready"
    }
    
    const tools = this.toolBox.getTools(context)
    // Create new toolSet instance with current context tools
    const toolSet = new ToolSet(tools)
    const threadId = context.data.openaiData.threadId
    
    await this.openai!.beta.threads.messages.create(threadId!, {
      role: "user",
      content: command,
    })
    const assistantStream = this.openai!.beta.threads.runs.stream(
      threadId!,
      {
        assistant_id: this.assistant!.id,
        tools: [...tools, {type: "file_search"}],
        tool_choice: "auto",
        max_completion_tokens: 120000,
        max_prompt_tokens: 120000,
        parallel_tool_calls: false,
      },
    )
    
    await this.processStream(assistantStream, toolSet, threadId)
    
    await assistantStream.finalRun()
    
    return this.textAccumulator
  }
  
  reset(): void {
    this.interactor.displayText("Thread has been reset")
  }
  
  private isOpenaiReady(): boolean {
    this.apiKey = this.apiKeyProvider()
    if (!this.apiKey) {
      this.interactor.warn(
        "OPENAI_API_KEY not set, skipping AI command",
      )
      return false
    }
    
    if (!this.openai) {
      this.openai = new OpenAI({
        apiKey: this.apiKey,
        baseURL: this.apiUrl
      })
    }
    return true
  }
  
  private async createThread(context: CommandContext): Promise<void> {
    const openaiData = context.data.openaiData
    if (!openaiData) {
      context.data.openaiData = {}
    }
    
    const threadId = context.data.openaiData?.threadId
    if (!threadId) {
      const thread = await this.openai!.beta.threads.create()
      context.data.openaiData.threadId = thread.id
      this.interactor.displayText(`Thread created with ID: ${thread.id}`)
      
      await this.openai!.beta.threads.messages.create(thread.id, {
        role: "assistant",
        content: context.project.description,
      })
    }
  }
  
  private async processStream(stream: AssistantStream, toolSet: ToolSet, threadId: string) {
    // Check interruption before starting stream processing
    stream.on("textDone", (diff) => {
      this.interactor.displayText(diff.value, this.assistant?.name)
      this.textAccumulator += diff.value
    })
    for await (const chunk of stream) {
      this.interactor.thinking()
      if (chunk.event === "thread.run.requires_action") {
        try {
          const toolCalls =
            chunk.data.required_action?.submit_tool_outputs.tool_calls ?? []
          const toolOutputs = await Promise.all(
            toolCalls.map(async (call) => {
              const toolCall: ToolCall = {
                id: call.id,
                name: call.function.name,
                args: call.function.arguments
              }
              const toolRequest = new ToolRequestEvent(toolCall)
              const output = await toolSet.runTool(toolRequest)
              return ({tool_call_id: call.id, output})
            }),
          )
          
          const newStream =
            this.openai!.beta.threads.runs.submitToolOutputsStream(
              threadId!,
              chunk.data.id,
              {tool_outputs: toolOutputs},
            )
          if (this.killed) return
          await this.processStream.call(this, newStream, toolSet, threadId)
        } catch (error) {
          console.error(`Error processing tool call`, error)
        }
      }
    }
  }

// TODO: move this out, it should be on the project object rather than here
  private async initAssistantList(): Promise<void> {
    // init map name -> id
    if (!this.assistants.length) {
      let after: string | undefined = undefined
      do {
        const fetchedAssistants: Assistant[] = (
          await this.openai!.beta.assistants.list({
            order: "asc",
            after,
            limit: 100,
          }).withResponse()
        ).data.getPaginatedItems()
        this.assistants.push(
          ...fetchedAssistants
            .filter((a) => !!a.name)
            .map((a) => ({
              name: a.name as string,
              id: a.id,
            })),
        )
        after =
          fetchedAssistants.length > 0
            ? fetchedAssistants[fetchedAssistants.length - 1].id
            : undefined
      } while (after)
    }
  }
  
  
  private async findAssistant(
    name: string,
    context: CommandContext,
  ): Promise<AssistantReference> {
    await this.initAssistantList()
    
    // then find all names that could match the given one
    const matchingAssistants = this.assistants.filter((a) =>
      a.name.toLowerCase().startsWith(name),
    )
    
    if (matchingAssistants.length === 1) {
      return matchingAssistants[0]
    }
    
    if (matchingAssistants.length > 1) {
      const selection = await this.interactor.chooseOption(
        matchingAssistants.map((m) => m.name),
        "Choose an assistant",
        `Found ${matchingAssistants.length} assistants that start with ${name}/`,
      )
      return matchingAssistants.find((m) => m.name === selection)!
    }
    
    // no existing assistant found, let's check the project ones that do have systemInstructions
    const projectAssistants = context.project.assistants
      ? [DEFAULT_DESCRIPTION, ...context.project.assistants]
      : undefined
    const matchingProjectAssistants = projectAssistants?.filter(
      (a) => a.name.toLowerCase().startsWith(name) && !!a.systemInstructions,
    )
    
    if (!matchingProjectAssistants?.length) {
      throw new Error("No matching assistant")
    }
    
    let assistantToCreate: AssistantDescription | undefined
    if (matchingProjectAssistants?.length === 1) {
      assistantToCreate = matchingProjectAssistants[0]
    }
    if (matchingProjectAssistants?.length > 1) {
      const selection = await this.interactor.chooseOption(
        matchingAssistants.map((m) => m.name),
        "Choose an assistant",
        `Found ${matchingProjectAssistants?.length} project assistants that start with ${name}/`,
      )
      assistantToCreate = matchingProjectAssistants.find(
        (m) => m.name === selection,
      )
    }
    
    if (!assistantToCreate) {
      throw new Error("No matching assistant")
    }
    
    this.interactor.displayText(
      `No existing assistant found for ${name}, will try to create it`,
    )
    
    
    const createdAssistant = await this.openai!.beta.assistants.create({
      name: assistantToCreate?.name,
      model: assistantToCreate.model ?? DEFAULT_MODEL,
      instructions: assistantToCreate?.systemInstructions,
      temperature: assistantToCreate.temperature ?? DEFAULT_TEMPERATURE,
    })
    this.interactor.displayText(`Created assistant ${createdAssistant.id}`)
    const createdReference: AssistantReference = {
      name: assistantToCreate.name,
      id: createdAssistant.id,
    }
    this.assistants.push(createdReference)
    
    return createdReference
  }
}
