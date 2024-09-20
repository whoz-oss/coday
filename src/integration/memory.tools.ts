import {memoryService} from "../service/memory.service"
import {CommandContext, Interactor} from "../model"
import {AssistantToolFactory, Tool} from "./assistant-tool-factory"
import {FunctionTool} from "./types"
import {integrationService} from "../service/integration.service"
import {MemoryLevel} from "../model/memory"

const MemoryLevels = [MemoryLevel.PROJECT, MemoryLevel.USER]

export class MemoryTools extends AssistantToolFactory {
  
  constructor(interactor: Interactor) {
    super(interactor)
  }
  
  protected hasChanged(context: CommandContext): boolean {
    return context.project.name !== this.lastToolInitContext?.project.name
  }
  
  protected buildTools(context: CommandContext): Tool[] {
    const result: Tool[] = []
    
    if (!integrationService.hasIntegration("MEMORY")) {
      return result
    }
    
    const memorize = async ({title, content, level}: { title: string, content: string, level: string }) => {
      const parsedLevel: MemoryLevel = level === "USER" ? MemoryLevel.USER : MemoryLevel.PROJECT
      
      memoryService.upsertMemory({title, content, level: parsedLevel})
      this.interactor.displayText(`Added ${parsedLevel} memory : ${title}\n${content}`)
      return `Memory added with title: ${title}`
    }
    
    const addMemoryTool: FunctionTool<{ title: string, content: string, level: string }> = {
      type: "function",
      function: {
        name: "memorize",
        description: `Upsert a memory entry to remember on next runs, use freely whenever encountering some knowledge that is relevant to the allowed levels. Allowed levels: ${MemoryLevels.join(", ")}.`,
        parameters: {
          type: "object",
          properties: {
            title: {type: "string", description: "Title of the memory, should be at most a sentence."},
            content: {
              type: "string",
              description: "Content of the memory, to expand with all details relevant to remember, length preferably between a paragraph and a 3 page long text."
            },
            level: {
              type: "string",
              description: "Level of the memory",
              enum: MemoryLevels
            }
          }
        },
        parse: JSON.parse,
        function: memorize
      }
    }
    result.push(addMemoryTool)
    
    return result
  }
  
}
