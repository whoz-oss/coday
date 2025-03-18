import * as fs from 'fs/promises'
import * as path from 'path'
import * as yaml from 'yaml'
import { AiClientProvider } from '../integration/ai/ai-client-provider'
import { Toolbox } from '../integration/toolbox'
import { Agent, AgentDefinition, AgentSummary, CodayAgentDefinition, CommandContext, Interactor } from '../model'
import { CodayServices } from '../coday-services'
import { ToolSet } from '../integration/tool-set'
import { getFormattedDocs } from '../function/get-formatted-docs'
import { MemoryLevel } from '../model/memory'
import { findFilesByName } from '../function/find-files-by-name'

export class AgentService {
  private agents: Map<string, Agent> = new Map()
  private agentDefinitions: AgentDefinition[] = []
  private toolbox: Toolbox

  constructor(
    private interactor: Interactor,
    private aiClientProvider: AiClientProvider,
    private services: CodayServices,
    private projectPath: string
  ) {
    // Subscribe to project changes to reset agents
    this.services.project.selectedProject$.subscribe(() => {
      this.agents.clear()
    })
    this.toolbox = new Toolbox(this.interactor, services, this)
  }

  listAgentSummaries(): AgentSummary[] {
    return this.agentDefinitions.map((a) => ({ name: a.name, description: a.description }))
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
          this.addDefinition(def)
        }
      }

      // Load from project local configuration
      const selectedProject = this.services.project.selectedProject
      if (selectedProject?.config.agents?.length) {
        for (const def of selectedProject.config.agents) {
          this.addDefinition(def)
        }
      }

      // Then load from files
      await this.loadFromFiles(context)

      // If no agents were loaded, use Coday as backup
      if (this.agents.size === 0) {
        this.addDefinition(CodayAgentDefinition)
      }
    } catch (error) {
      console.error('Failed to initialize agents:', error)
      throw error
    }

    await Promise.all(this.agentDefinitions.map((def) => this.tryAddAgent(def, context)))
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
    const defaultAgentName = 'coday'
    
    // Initialize agents if not already done
    await this.initialize(context)
    
    // If no nameStart is provided, check for user's preferred agent
    if (!nameStart) {
      const projectName = this.services.project.selectedProject?.name
      if (projectName) {
        const userConfig = this.services.user.config
        const preferredAgent = userConfig.projects?.[projectName]?.defaultAgent
        
        if (preferredAgent) {
          // Try to find the preferred agent
          const agent = this.agents.get(preferredAgent.toLowerCase())
          if (agent) {
            return agent
          }
          // If preferred agent not found, log a warning and continue with default
          this.interactor.warn(`Preferred agent '${preferredAgent}' not found, using default.`)
        }
      }
    }
    
    const matchingAgents = await this.findAgentsByNameStart(nameStart ?? defaultAgentName, context)

    if (matchingAgents.length === 0) {
      return this.agents.get(defaultAgentName.toLowerCase())
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
        const selectedAgent = matchingAgents.find((agent) => agent.name === selection)
        return selectedAgent
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
    const matches: Agent[] = []

    for (const name of Array.from(this.agents.keys())) {
      if (name.startsWith(lowerNameStart)) {
        matches.push(this.agents.get(name)!)
      }
    }

    return matches
  }

  kill(): void {
    this.aiClientProvider.kill()
  }

  private addDefinition(def: AgentDefinition): void {
    if (!this.agentDefinitions.find((a) => a.name === def.name)) {
      this.agentDefinitions.push({ ...CodayAgentDefinition, ...def })
    }
  }

  /**
   * Load agent definitions from ~/.coday/[project]/agents/ folder
   * Each file should contain a single agent definition
   */
  private async loadFromFiles(context: CommandContext): Promise<void> {
    const agentsPaths: string[] = []
    const agentFiles: string[] = []
    const selectedProject = this.services.project.selectedProject
    if (selectedProject) {
      agentsPaths.push(path.join(selectedProject.configPath, 'agents'))
    }
    const projectPath = this.services.project.selectedProject?.config.path
    if (projectPath) {
      const codayFolder = path.dirname((await findFilesByName({ text: 'coday.yaml', root: projectPath }))[0])

      agentsPaths.push(path.join(projectPath, codayFolder, 'agents'))
    }

    await Promise.all(
      agentsPaths.map(async (agentsPath) => {
        try {
          const files = await fs.readdir(agentsPath)
          for (const file of files) {
            if (!file.endsWith('.yml') && !file.endsWith('.yaml')) continue
            agentFiles.push(path.join(agentsPath, file))
          }
        } catch (e) {
          console.error(e)
        }
      })
    )

    await Promise.all(
      agentFiles.map(async (agentFilePath) => {
        try {
          const content = await fs.readFile(agentFilePath, 'utf-8')
          const data = yaml.parse(content)
          this.addDefinition(data)
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
  private async tryAddAgent(partialDef: AgentDefinition, context: CommandContext): Promise<void> {
    const def: AgentDefinition = { ...CodayAgentDefinition, ...partialDef }

    // force aiProvider for OpenAI assistants
    if (def.openaiAssistantId) def.aiProvider = 'openai'

    if (!this.aiClientProvider || !this.toolbox) {
      console.error(`Cannot create agent ${def.name}: dependencies not set. Call setDependencies first.`)
      return
    }

    const aiClient = this.aiClientProvider.getClient(def.aiProvider)
    if (!aiClient) {
      // Provide more specific error for localLlm
      if (def.aiProvider === 'localLlm') {
        this.interactor.warn(
          `Cannot create agent ${def.name}: Local LLM configuration is missing or incomplete. ` +
            `Please configure 'url' in your aiProviders section in ~/.coday/users/${this.services.user.sanitizedUsername}/user.yml or through 'config ai user'`
        )
      } else {
        console.error(`Cannot create agent ${def.name}: AI client creation failed`)
      }
      return
    }

    const agentDocs = getFormattedDocs(def, this.interactor, this.projectPath)

    const instructions = `${def.instructions}\n\n
## Project description
${context.project.description}

${this.services.memory.getFormattedMemories(MemoryLevel.USER, def.name)}

${this.services.memory.getFormattedMemories(MemoryLevel.PROJECT, def.name)}

${agentDocs}

`
    // overwrite agent instructions with the added project and user context
    def.instructions = instructions

    const integrations = def.integrations
      ? new Map<string, string[]>(
          Object.entries(def.integrations).map(([integration, names]: [string, string[]]): [string, string[]] => {
            const toolNames: string[] = !names || !names.length ? [] : names
            return [integration, toolNames]
          })
        )
      : undefined

    const syncTools = this.toolbox.getTools({ context, integrations, agentName: def.name })
    const asyncTools = await this.toolbox.getAsyncTools(context)
    const toolset = new ToolSet([...syncTools, ...asyncTools])
    const agent = new Agent(def, aiClient, toolset)
    this.agents.set(agent.name.toLowerCase(), agent)
  }
}
