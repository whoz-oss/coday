import { MemoryService } from '../service/memory.service'
import { CommandContext, Interactor } from '../model'
import { AssistantToolFactory, CodayTool } from './assistant-tool-factory'
import { FunctionTool } from './types'
import { MemoryLevel } from '../model/memory'

const MemoryLevels = [MemoryLevel.PROJECT, MemoryLevel.USER]

export class MemoryTools extends AssistantToolFactory {
  constructor(
    interactor: Interactor,
    private memoryService: MemoryService
  ) {
    super(interactor)
  }

  protected hasChanged(context: CommandContext): boolean {
    return context.project.name !== this.lastToolInitContext?.project.name
  }

  protected buildTools(context: CommandContext): CodayTool[] {
    const result: CodayTool[] = []

    const memorize = async ({ title, content, level }: { title: string; content: string; level: string }) => {
      const parsedLevel: MemoryLevel = level === 'USER' ? MemoryLevel.USER : MemoryLevel.PROJECT

      this.memoryService.upsertMemory({ title, content, level: parsedLevel })
      this.interactor.displayText(`Added ${parsedLevel} memory : ${title}\n${content}`)
      return `Memory added with title: ${title}`
    }

    const addMemoryTool: FunctionTool<{ title: string; content: string; level: string }> = {
      type: 'function',
      function: {
        name: 'memorize',
        description: `Upsert a memory entry to remember on next runs. Should be used selectively for significant, complete, and validated knowledge that: 1) represents core project/user patterns, 2) will be valuable in multiple future interactions, 3) is not redundant with existing memories. Do not memorize partial knowledge, single-use information, or minor implementation details. Allowed levels: ${MemoryLevels.join(', ')}.`,
        parameters: {
          type: 'object',
          properties: {
            title: {
              type: 'string',
              description:
                'Title of the memory: should be precise, unique, and reflect the full scope of the content. Avoid generic titles.',
            },
            content: {
              type: 'string',
              description:
                'Content of the memory: must be complete, validated knowledge with clear structure (sections, examples when relevant). Should include enough context to be self-contained but avoid redundancy with other memories. Length: preferably between a paragraph (for focused topics) and 3 pages (for complex patterns).',
            },
            level: {
              type: 'string',
              description:
                'Level of the memory: PROJECT for architectural decisions, core patterns, or significant design guidelines; USER for strong personal preferences or validated working patterns that impact multiple interactions.',
              enum: MemoryLevels,
            },
          },
        },
        parse: JSON.parse,
        function: memorize,
      },
    }
    result.push(addMemoryTool)

    const deleteMemoryFunction = async ({ title }: { title: string }) => {
      try {
        this.memoryService.deleteMemory(title)
        this.interactor.displayText(`Deleted memory: ${title}`)
        return `Memory deleted: ${title}`
      } catch (error) {
        const errorMessage = `Failed to delete memory: ${error instanceof Error ? error.message : 'Unknown error'}`
        this.interactor.error(errorMessage)
        return errorMessage
      }
    }

    const deleteMemoryTool: FunctionTool<{ title: string }> = {
      type: 'function',
      function: {
        name: 'deleteMemory',
        description: 'Delete a memory entry by its title.',
        parameters: {
          type: 'object',
          properties: {
            title: { type: 'string', description: 'Title of the memory to delete.' },
          },
        },
        parse: JSON.parse,
        function: deleteMemoryFunction,
      },
    }
    result.push(deleteMemoryTool)

    return result
  }
}
