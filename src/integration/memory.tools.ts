import {memoryService} from "../service/memory-service"
import {CommandContext, IntegrationName, Interactor} from "../model"
import {AssistantToolFactory, Tool} from "./assistant-tool-factory"
import {FunctionTool} from "./types"
import {integrationService} from "../service/integration.service"
import {MemoryLevel} from "../model/memory"

const LEVEL_MAPPING: Map<MemoryLevel, IntegrationName> = new Map([[MemoryLevel.USER, IntegrationName.USER_MEMORY], [MemoryLevel.PROJECT, IntegrationName.PROJECT_MEMORY]])

export class MemoryTools extends AssistantToolFactory {
  
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return context.project.name !== this.lastToolInitContext?.project.name
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    const allowedLevels = [...LEVEL_MAPPING.keys()].filter(key => integrationService.hasIntegration(LEVEL_MAPPING.get(key)!!))
    
    if (!allowedLevels.length) {
      return result
    }
    
    const addMemoryFunction = async ({title, content, level}: { title: string, content: string, level: string }) => {
      const parsedLevel: MemoryLevel = level === "USER" ? MemoryLevel.USER : MemoryLevel.PROJECT
      if (!allowedLevels.includes(parsedLevel)) {
        throw new Error(`Level ${parsedLevel} not allowed.`)
      }
      memoryService.upsertMemory({title, content, level: parsedLevel})
      this.interactor.displayText(`Added ${parsedLevel} memory : ${title}`)
      return `Memory added with title: ${title}`
    }
    
    const addMemoryTool: FunctionTool<{ title: string, content: string, level: string }> = {
      type: "function",
      function: {
        name: "addMemory",
        description: `Add a new memory entry to remember on next runs, use freely whenever encountering some broad knowledge that is relevant to the allowed levels. Allowed levels: ${allowedLevels.join(", ")}. For example, do not record information about a very specific file or a very odd demand, but record patterns accross multiple files or behaviors, attitudes asked for that should be recurrent in time.`,
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
              enum: allowedLevels
            }
          }
        },
        parse: JSON.parse,
        function: addMemoryFunction
      }
    }
    result.push(addMemoryTool)
    
    return result
  }
  
}
