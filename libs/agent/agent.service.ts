import * as fs from 'fs/promises'
import * as path from 'path'
import * as yaml from 'yaml'
import { AiClientProvider } from '../integration/ai/ai-client-provider'
import { Toolbox } from '../integration/toolbox'
import {
  Agent,
  AgentDefinition,
  AgentSummary,
  CodayAgentDefinition,
  CommandContext,
  Interactor,
  Killable,
} from '../model'
import { CodayServices } from '../coday-services'
import { ToolSet } from '../integration/tool-set'
import { getFormattedDocs } from '../function/get-formatted-docs'
import { MemoryLevel } from '../model/memory'
import { findFilesByName } from '../function/find-files-by-name'

export class AgentService implements Killable {
  private agents: Map<string, Agent> = new Map()
  private agentDefinitions: { definition: AgentDefinition; basePath: string }[] = []
  private toolbox: Toolbox

  constructor(
    private interactor: Interactor,
    private aiClientProvider: AiClientProvider,
    private services: CodayServices,
    private projectPath: string,
    private commandLineAgentFolders: string[] = []
  ) {
    // Subscribe to project changes to reset agents
    this.services.project.selectedProject$.subscribe(() => {
      this.agents.clear()
    })
    this.toolbox = new Toolbox(this.interactor, services, this)
  }

  listAgentSummaries(): AgentSummary[] {
    return this.agentDefinitions
      .map((entry) => ({
        name: entry.definition.name,
        description: entry.definition.description,
      }))
      .sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
  }

  /**
   * Initialize agent definitions from all sources if not already initialized:
   * - coday.yml agents section
   * - project local configuration agents
   * - ~/.coday/[project]/agents/ folder
   */
  async initialize(context: CommandContext): Promise<void> {
    // Already initialized if we have any agents
    if (this.agents.size > 0) return

    try {
      // Load from coday.yml agents section first
      if (context.project.agents?.length) {
        for (const def of context.project.agents) {
          this.addDefinition(def, this.projectPath)
        }
      }

      // Load from project local configuration
      const selectedProject = this.services.project.selectedProject
      if (selectedProject?.config.agents?.length) {
        for (const def of selectedProject.config.agents) {
          this.addDefinition(def, this.projectPath)
        }
      }

      // Then load from files
      await this.loadFromFiles(context)

      // If no agents were loaded, use Coday as backup
      if (this.agents.size === 0) {
        this.addDefinition(CodayAgentDefinition, this.projectPath)
      }
    } catch (error: unknown) {
      this.interactor.error(`Failed to initialize agents: ${error}`)
      throw error
    }

    const allAgents = await Promise.all(this.agentDefinitions.map((entry) => this.tryAddAgent(entry, context)))
    allAgents.filter((agent) => !!agent).forEach((agent) => this.agents.set(agent.name.toLowerCase(), agent))
    const agentNames = this.listAgentSummaries().map((a) => `  - ${a.name} : ${a.description}`)
    if (agentNames.length > 1) {
      this.interactor.displayText(`Loaded agents (callable with '@[agent name]'):\n${agentNames.join('\n')}`)
    }
  }

  /**
   * Find an agent by exact name match (case insensitive)
   */
  async findByName(name: string, context: CommandContext): Promise<Agent | undefined> {
    await this.initialize(context)
    return this.agents.get(name.toLowerCase())
  }

  async findAgentByNameStart(nameStart: string | undefined, context: CommandContext): Promise<Agent | undefined> {
    if (!nameStart) {
      return
    }

    // Initialize agents if not already done
    await this.initialize(context)

    const matchingAgents = await this.findAgentsByNameStart(nameStart?.toLowerCase() || '', context)

    if (matchingAgents.length === 0) {
      return undefined
    }

    if (matchingAgents.length === 1) {
      return matchingAgents[0]
    }

    if (!context.oneshot) {
      const options = matchingAgents.map((agent) => agent.name)
      try {
        const selection = await this.interactor.chooseOption(
          options,
          `Multiple agents match '${nameStart}', please select one:`
        )
        return matchingAgents.find((agent) => agent.name === selection)
      } catch (error) {
        this.interactor.error('Selection cancelled')
        return undefined
      }
    }
  }

  /**
   * Find agents by the start of their name (case insensitive)
   * For example, "fid" will match "Fido_the_dog"
   * Returns all matching agents or empty array if none found
   */
  async findAgentsByNameStart(nameStart: string, context: CommandContext): Promise<Agent[]> {
    await this.initialize(context)

    const lowerNameStart = nameStart.toLowerCase()
    return Array.from(this.agents.keys())
      .filter((agentName) => agentName.toLowerCase().startsWith(lowerNameStart))
      .map((agentName) => this.agents.get(agentName))
      .filter((agent) => !!agent)
  }

  /**
   * Get the user's preferred agent for the current project
   * @returns The name of the preferred agent or undefined if not set
   */
  getPreferredAgent(): string | undefined {
    const projectName = this.services.project.selectedProject?.name
    if (!projectName) return undefined

    const userConfig = this.services.user.config
    return userConfig.projects?.[projectName]?.defaultAgent
  }

  async kill(): Promise<void> {
    this.aiClientProvider.kill()
    await this.toolbox.kill()
  }

  private addDefinition(def: AgentDefinition, basePath: string = this.projectPath): void {
    if (!this.agentDefinitions.find((entry) => entry.definition.name === def.name)) {
      this.agentDefinitions.push({
        definition: { ...CodayAgentDefinition, ...def },
        basePath,
      })
    }
  }

  /**
   * Load agent definitions from all configured agent folders:
   * - ~/.coday/[project]/agents/ folder
   * - folder next to coday.yaml
   * - folders specified in coday.yaml agentFolders
   * - folders specified via command line options
   * Each file should contain a single agent definition
   */
  private async loadFromFiles(context: CommandContext): Promise<void> {
    const agentsPaths: string[] = []
    const agentFiles: string[] = []

    // Add path from user config
    const selectedProject = this.services.project.selectedProject
    if (selectedProject) {
      agentsPaths.push(path.join(selectedProject.configPath, 'agents'))
    }

    // Add path from project (next to coday.yaml)
    const projectPath = this.services.project.selectedProject?.config.path
    if (projectPath) {
      const codayFiles = await findFilesByName({ text: 'coday.yaml', root: projectPath })
      if (codayFiles.length > 0) {
        const codayFolder = path.dirname(codayFiles[0])
        agentsPaths.push(path.join(projectPath, codayFolder, 'agents'))
      }
      if (context.project.agentFolders?.length) {
        agentsPaths.push(...context.project.agentFolders)
      }
    }

    agentsPaths.push(...this.commandLineAgentFolders)

    await Promise.all(
      agentsPaths.map(async (agentsPath) => {
        try {
          agentFiles.push(
            ...(await fs.readdir(agentsPath))
              .filter((file) => file.endsWith('.yml') || file.endsWith('.yaml'))
              .map((file) => path.join(agentsPath, file))
          )
        } catch (e: any) {
          if (e.code === 'EPERM') {
            console.error(
              `Permission denied to access ${agentsPath}. This is common for protected directories.\nConsider moving your agent files to a less restricted location.`
            )
          } else {
            // For other errors, just log a simple message
            console.error(`Could not read directory ${agentsPath}: ${e.code}`)
          }
        }
      })
    )

    await Promise.all(
      agentFiles.map(async (agentFilePath) => {
        try {
          const content = await fs.readFile(agentFilePath, 'utf-8')
          const data = yaml.parse(content)

          // Determine the base path for document resolution
          const agentDirPath = path.dirname(agentFilePath)
          const isInProject = agentDirPath.startsWith(this.projectPath)

          // Add definition with the appropriate base path
          const basePath = isInProject ? this.projectPath : agentDirPath
          this.addDefinition(data, basePath)
        } catch (e) {
          console.error(e)
        }
      })
    )
  }

  /**
   * Try to create and add an agent to the map
   * Logs error if dependencies are missing
   */
  private async tryAddAgent(
    entry: {
      definition: AgentDefinition
      basePath: string
    },
    context: CommandContext
  ): Promise<Agent | undefined> {
    const def: AgentDefinition = { ...CodayAgentDefinition, ...entry.definition }

    try {
      // force aiProvider for OpenAI assistants
      if (def.openaiAssistantId) def.aiProvider = 'openai'

      if (!this.aiClientProvider || !this.toolbox) {
        console.error(`Cannot create agent ${def.name}: dependencies not set. Call setDependencies first.`)
        return
      }
      console.log(`getting client for agent ${def.name}, ${def.aiProvider}, ${def.modelName}`)
      const aiClient = this.aiClientProvider.getClient(def.aiProvider, def.modelName)
      if (!aiClient) {
        this.interactor.error(`Cannot create agent ${def.name}: AI client creation failed`)
        return
      }

      const basePath = entry.basePath
      const agentDocs = await getFormattedDocs(def, this.interactor, basePath, def.name)

      // overwrite agent instructions with the added project and user context
      def.instructions = `${def.instructions}\n\n
## Project description
${context.project.description}

${this.services.memory.getFormattedMemories(MemoryLevel.USER, def.name)}

${this.services.memory.getFormattedMemories(MemoryLevel.PROJECT, def.name)}

${agentDocs}

`

      const integrations = def.integrations
        ? new Map<string, string[]>(
            Object.entries(def.integrations).map(([integration, names]: [string, string[]]): [string, string[]] => {
              const toolNames: string[] = !names || !names.length ? [] : names
              return [integration, toolNames]
            })
          )
        : undefined
      const syncTools = await this.toolbox.getTools({ context, integrations, agentName: def.name })

      const toolset = new ToolSet([...syncTools])
      return new Agent(def, aiClient, toolset)
    } catch (error) {
      const errorMessage = `Failed to create agent ${def.name}`
      console.error(`${errorMessage}:`, error)
      this.interactor.error(errorMessage)
    }
  }
}
