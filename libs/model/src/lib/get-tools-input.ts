import { CommandContext } from './command-context'

export type IntegrationsSelection = Map<string, string[]>

export type GetToolsInput = {
  context: CommandContext
  integrations?: IntegrationsSelection
  agentName: string
}
