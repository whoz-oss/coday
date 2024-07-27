import OpenAI from "openai"
import {AssistantStream} from "openai/lib/AssistantStream"
import {Beta} from "openai/resources"
import {CODAY_DESCRIPTION} from "./openai/coday-description"
import {AssistantDescription, CommandContext, Interactor} from "../model"
import {AiClient} from "../model/ai.client"
import {AllTools} from "../integration/all.tools"
import {Tool} from "../integration/assistant-tool-factory"
import {runTool} from "../integration/run-tool"
import {ToolCall} from "../integration/tool-call"
import Assistant = Beta.Assistant

const DEFAULT_MODEL: string = "gpt-4o"
const DEFAULT_TEMPERATURE: number = 0.75

type AssistantReference = { name: string; id: string }

export class OpenaiClient implements AiClient {
  openai: OpenAI | undefined
  threadId: string | null = null
  textAccumulator: string = ""
  
  toolBox: AllTools
  apiKey: string | undefined
  assistants: AssistantReference[] = []
  assistant: AssistantReference | undefined
  
  constructor(
    private interactor: Interactor,
    private apiKeyProvider: () => string | undefined,
  ) {
    this.toolBox = new AllTools(interactor)
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
      })
    }
    return true
  }
  
  async isReady(
    assistantName: string,
    context: CommandContext,
  ): Promise<boolean> {
    if (!this.isOpenaiReady()) {
      return false
    }
    
    this.assistant = await this.findAssistant(assistantName, context)
    
    if (!this.threadId) {
      const thread = await this.openai!.beta.threads.create()
      this.threadId = thread.id
      this.interactor.displayText(`Thread created with ID: ${this.threadId}`)
      
      await this.openai!.beta.threads.messages.create(this.threadId, {
        role: "assistant",
        content: `Specific project context: ${context.project.description}

                You are interacting with a human with username:${context.username}
                `,
      })
      
      const projectAssistants = this.getProjectAssistants(context)
      const projectAssistantReferences = projectAssistants?.map(
        (a) => `${a.name} : ${a.description}`,
      )
      if (projectAssistantReferences?.length) {
        await this.openai!.beta.threads.messages.create(this.threadId, {
          role: "assistant",
          content: `IMPORTANT!
                    Here the assistants available on this project (by name : description) : \n- ${projectAssistantReferences.join("\n- ")}\n

                    Rules:
                    - **Active delegation**: Always delegate parts of complex requests to the relevant assistants given their domain of expertise.
                    - **Coordinator**: ${CODAY_DESCRIPTION.name} coordinate the team and have a central role
                    - **Calling**: To involve an assistant in the thread, mention it with an '@' prefix on their name and explain what is expected from him. The called assistant will be called after the current run. Example: '... and by the way, @otherAssistant, check this part of the request'.`,
        })
      }
    }
    
    return true
  }
  
  async addMessage(message: string): Promise<void> {
    if (!this.threadId || !this.openai) {
      throw new Error("Cannot add message if no thread or openai defined yet")
    }
    
    await this.openai!.beta.threads.messages.create(this.threadId, {
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
    
    await this.openai!.beta.threads.messages.create(this.threadId!, {
      role: "user",
      content: command,
    })
    const assistantStream = this.openai!.beta.threads.runs.stream(
      this.threadId!,
      {
        assistant_id: this.assistant!.id,
        tools,
        tool_choice: "auto",
        max_completion_tokens: 120000,
        max_prompt_tokens: 120000,
        parallel_tool_calls: false,
      },
    )
    
    await this.processStream(assistantStream, tools)
    
    await assistantStream.finalRun()
    
    return this.textAccumulator
  }
  
  private async processStream(stream: AssistantStream, tools: Tool[]) {
    stream.on("textDone", (diff) => {
      this.interactor.displayText(diff.value, this.assistant?.name)
      this.textAccumulator += diff.value
    })
    for await (const chunk of stream) {
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
              const output = await runTool(toolCall, tools, this.interactor)
              
              return {
                tool_call_id: {
                  id: call.id,
                  name: call.function.name,
                  args: call.function.arguments
                }.id, output
              }
            }),
          )
          
          const newStream =
            this.openai!.beta.threads.runs.submitToolOutputsStream(
              this.threadId!,
              chunk.data.id,
              {tool_outputs: toolOutputs},
            )
          
          await this.processStream.call(this, newStream, tools)
        } catch (error) {
          console.error(`Error processing tool call`, error)
        }
      }
    }
  }
  
  reset(): void {
    this.threadId = null
    this.interactor.displayText("Thread has been reset")
  }
  
  // TODO: move this out, it should be on the project object rather than here
  getProjectAssistants(
    context: CommandContext,
  ): AssistantDescription[] | undefined {
    return context.project.assistants
      ? [CODAY_DESCRIPTION, ...context.project.assistants]
      : undefined
  }
  
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
    const projectAssistants = this.getProjectAssistants(context)
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
