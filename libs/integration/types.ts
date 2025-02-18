import { CommandContext } from '../model'

export interface FunctionDefinition<T> {
  name: string
  description: string
  parameters: { type: 'object'; properties: Record<string, unknown> }
  parse: (input: string) => T
  function: (args: T) => Promise<unknown> | unknown
}

export interface FunctionTool<T> {
  type: 'function'
  function: FunctionDefinition<T>
}

export type IntegrationsSelection = Map<string, string[]>

export type GetToolsInput = {
  context: CommandContext
  integrations?: IntegrationsSelection
  agentName: string
}
