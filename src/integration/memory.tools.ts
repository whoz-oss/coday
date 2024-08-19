import {memoryService} from "../service/memory-service"
import {CommandContext, IntegrationName, Interactor} from "../model"
import {AssistantToolFactory, Tool} from "./assistant-tool-factory"
import {FunctionTool} from "./types"
import {integrationService} from "../service/integration.service"
import {MemoryLevel} from "../model/memory"

const LEVEL_MAPPING: Map<MemoryLevel, IntegrationName> = new Map([[MemoryLevel.USER, IntegrationName.USER_MEMORY], [MemoryLevel.PROJECT, IntegrationName.PROJECT_MEMORY]])

export class MemoryTools extends AssistantToolFactory {
  private allowedLevels: MemoryLevel[] = []
  
  constructor(interactor: Interactor) {
    super(interactor)
    this.buildAllowedLevels()
  }
  
  protected hasChanged(context: CommandContext): boolean {
    const changed = context.project.name !== this.lastToolInitContext?.project.name
    if (changed) {
      this.buildAllowedLevels()
    }
    return changed
  }
  
  private buildAllowedLevels(): void {
    this.allowedLevels = [...LEVEL_MAPPING.keys()].filter(key => integrationService.hasIntegration(LEVEL_MAPPING.get(key)!!))
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    
    
    if (!this.allowedLevels.length) {
      return result
    }
    
    const addMemoryFunction = async ({title, content, level}: { title: string, content: string, level: string }) => {
      const parsedLevel: MemoryLevel = level === "USER" ? MemoryLevel.USER : MemoryLevel.PROJECT
      if (!this.allowedLevels.includes(parsedLevel)) {
        throw new Error(`Level ${parsedLevel} not allowed.`)
      }
      memoryService.addMemory({title, content, level: parsedLevel})
      this.interactor.displayText(`Added ${parsedLevel} memory : ${title}`)
      return `Memory added with title: ${title}`
    }
    
    const addMemoryTool: FunctionTool<{ title: string, content: string, level: string }> = {
      type: "function",
      function: {
        name: "addMemory",
        description: `Add a new memory entry to remember on next runs, use freely whenever encountering some broad knowledge that is relevant to the allowed levels. Allowed levels: ${this.allowedLevels.join(", ")}. For example, do not record information about a very specific file or a very odd demand, but record patterns accross multiple files or behaviors, attitudes asked for that should be recurrent in time.`,
        parameters: {
          type: "object",
          properties: {
            title: {type: "string", description: "Title of the memory, should be at most a sentence and unique"},
            content: {
              type: "string",
              description: "Content of the memory, to expand with all details relevant to remember"
            },
            level: {
              type: "string",
              description: "Level of the memory",
              enum: this.allowedLevels
            }
          }
        },
        parse: JSON.parse,
        function: addMemoryFunction
      }
    }
    result.push(addMemoryTool)
    console.log(result)
    return result
  }
  
}
