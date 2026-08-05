import { MemoryService } from '@coday/service'
import { AssistantToolFactory, Interactor, IntegrationConfig } from '@coday/model'
import { CommandContext } from '@coday/model'
import { CodayTool } from '@coday/model'
import { MemoryLevel } from '@coday/model'
import { FunctionTool } from '@coday/model'

export class MemoryTools extends AssistantToolFactory {
  static readonly TYPE = 'MEMORY' as const

  constructor(
    interactor: Interactor,
    private readonly memoryService: MemoryService,
    instanceName: string,
    config: IntegrationConfig
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(_context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []

    // Tool to read memories
    const readMemoriesFunction = async ({ title }: { title?: string }) => {
      try {
        if (title) {
          // Return specific memory content
          const projectMemories = this.memoryService.listMemories(MemoryLevel.PROJECT, agentName)
          const userMemories = this.memoryService.listMemories(MemoryLevel.USER, agentName)
          const learningMemories = this.memoryService.listMemories(MemoryLevel.LEARNING, agentName)
          const allMemories = [...projectMemories, ...userMemories, ...learningMemories]

          const memory = allMemories.find((m) => m.title === title)
          if (!memory) {
            return `Memory with title '${title}' not found.`
          }

          return `# ${memory.title}\n\n**Level:** ${memory.level}\n\n${memory.content}`
        } else {
          // Return list of memories with titles and levels
          const projectMemories = this.memoryService.listMemories(MemoryLevel.PROJECT, agentName)
          const userMemories = this.memoryService.listMemories(MemoryLevel.USER, agentName)
          const learningMemories = this.memoryService.listMemories(MemoryLevel.LEARNING, agentName)

          if (projectMemories.length === 0 && userMemories.length === 0 && learningMemories.length === 0) {
            return 'No memories found.'
          }

          let result = '# Available Memories\n\n'

          if (projectMemories.length > 0) {
            result += '## PROJECT Level\n'
            projectMemories.forEach((m) => {
              result += `- ${m.title}\n`
            })
            result += '\n'
          }

          if (userMemories.length > 0) {
            result += '## USER Level\n'
            userMemories.forEach((m) => {
              result += `- ${m.title}\n`
            })
            result += '\n'
          }

          if (learningMemories.length > 0) {
            result += '## LEARNING Level\n'
            learningMemories.forEach((m) => {
              result += `- ${m.title}\n`
            })
          }

          return result
        }
      } catch (error) {
        const errorMessage = `Failed to read memories: ${error instanceof Error ? error.message : 'Unknown error'}`
        this.interactor.error(errorMessage)
        return errorMessage
      }
    }

    const readMemoriesTool: FunctionTool<{ title?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__read`,
        description: `Read agent's memories. Without title parameter, returns the list of all memories with their titles and levels. With title parameter, returns the full content of the specified memory.`,
        parameters: {
          type: 'object',
          properties: {
            title: {
              type: 'string',
              description: 'Optional: Title of the specific memory to read. If not provided, lists all memories.',
            },
          },
        },
        parse: JSON.parse,
        function: readMemoriesFunction,
      },
    }
    result.push(readMemoriesTool)

    // Tool to memorize at PROJECT level
    const memorizeProjectFunction = async ({ title, content }: { title: string; content: string }) => {
      try {
        this.memoryService.upsertMemory({ title, content, level: MemoryLevel.PROJECT, agentName })
        return `PROJECT memory added/updated with title: ${title}`
      } catch (error) {
        const errorMessage = `Failed to memorize at PROJECT level: ${error instanceof Error ? error.message : 'Unknown error'}`
        this.interactor.error(errorMessage)
        return errorMessage
      }
    }

    const memorizeProjectTool: FunctionTool<{ title: string; content: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__memorizeProject`,
        description: `Upsert a PROJECT-level memory entry. PROJECT memories are for:
- Architectural decisions and core patterns
- Significant design guidelines
- Project-specific conventions and standards
- Technical decisions that impact the entire project

Should be used selectively for significant knowledge that:
1) represents core project patterns
2) will be valuable in multiple future interactions
3) is not redundant with existing memories (in that case, update the existing one)

Do not memorize partial knowledge, single-use information, or minor implementation details.`,
        parameters: {
          type: 'object',
          properties: {
            title: {
              type: 'string',
              description:
                'Title of the memory: should be precise, unique, and reflect the full scope of the content. Avoid generic titles unless the content is really generic.',
            },
            content: {
              type: 'string',
              description:
                'Content of the memory: must be complete, validated knowledge with clear structure (sections, examples when relevant). Should include enough context to be self-contained but avoid redundancy with other memories. Length: preferably between a paragraph (for focused topics) and 3 pages (for complex patterns).',
            },
          },
        },
        parse: JSON.parse,
        function: memorizeProjectFunction,
      },
    }
    result.push(memorizeProjectTool)

    // Tool to memorize at USER level
    const memorizeUserFunction = async ({ title, content }: { title: string; content: string }) => {
      try {
        this.memoryService.upsertMemory({ title, content, level: MemoryLevel.USER, agentName })
        return `USER memory added/updated with title: ${title}`
      } catch (error) {
        const errorMessage = `Failed to memorize at USER level: ${error instanceof Error ? error.message : 'Unknown error'}`
        this.interactor.error(errorMessage)
        return errorMessage
      }
    }

    const memorizeUserTool: FunctionTool<{ title: string; content: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__memorizeUser`,
        description: `Upsert a USER-level memory entry. USER memories are for:
- Strong personal preferences
- User-specific working patterns
- Individual communication styles
- Personal context that impacts interactions

Should be used selectively for significant knowledge that:
1) represents validated user patterns
2) will be valuable in multiple future interactions
3) is not redundant with existing memories (in that case, update the existing one)

Do not memorize partial knowledge, single-use information, or minor implementation details.`,
        parameters: {
          type: 'object',
          properties: {
            title: {
              type: 'string',
              description:
                'Title of the memory: should be precise, unique, and reflect the full scope of the content. Avoid generic titles unless the content is really generic.',
            },
            content: {
              type: 'string',
              description:
                'Content of the memory: must be complete, validated knowledge with clear structure (sections, examples when relevant). Should include enough context to be self-contained but avoid redundancy with other memories. Length: preferably between a paragraph (for focused topics) and 3 pages (for complex patterns).',
            },
          },
        },
        parse: JSON.parse,
        function: memorizeUserFunction,
      },
    }
    result.push(memorizeUserTool)

    // Tool to memorize at LEARNING level
    const memorizeLearningFunction = async ({ title, content }: { title: string; content: string }) => {
      try {
        this.memoryService.upsertMemory({ title, content, level: MemoryLevel.LEARNING, agentName })
        return `LEARNING memory added/updated with title: ${title}`
      } catch (error) {
        const errorMessage = `Failed to memorize at LEARNING level: ${error instanceof Error ? error.message : 'Unknown error'}`
        this.interactor.error(errorMessage)
        return errorMessage
      }
    }

    const memorizeLearningTool: FunctionTool<{ title: string; content: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__memorizeLearning`,
        description: `Upsert a LEARNING-level memory entry. LEARNING memories are for:
- Technical knowledge discovered during conversations
- Corrections and validated approaches
- Hard-won resolutions and workarounds
- Non-obvious patterns and conventions

LEARNING memories are NOT injected in every conversation. They are searched and retrieved on-demand based on relevance to the current message. This means you can store many learnings without worrying about context pollution.

Should be used selectively for significant knowledge that:
1) was discovered through conversation or debugging
2) will be valuable in future interactions
3) is not redundant with existing memories (in that case, update the existing one)

Do not memorize partial knowledge, single-use information, or minor implementation details.`,
        parameters: {
          type: 'object',
          properties: {
            title: {
              type: 'string',
              description:
                'Title of the memory: should be precise, unique, and reflect the full scope of the content. Avoid generic titles unless the content is really generic.',
            },
            content: {
              type: 'string',
              description:
                'Content of the memory: must be complete, validated knowledge with clear structure (sections, examples when relevant). Should include enough context to be self-contained but avoid redundancy with other memories. Length: preferably between a paragraph (for focused topics) and 3 pages (for complex patterns).',
            },
          },
        },
        parse: JSON.parse,
        function: memorizeLearningFunction,
      },
    }
    result.push(memorizeLearningTool)

    // Tool to delete memory
    const deleteMemoryFunction = async ({ title }: { title: string }) => {
      try {
        this.memoryService.deleteMemory(title)
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
        name: `${this.name}__delete`,
        description: 'Delete a memory entry by its title. Works for PROJECT, USER, and LEARNING level memories.',
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
