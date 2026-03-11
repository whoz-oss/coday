import { AiThread } from './ai-thread'

export interface FunctionDefinition<T> {
  name: string
  description: string
  parameters: { type: 'object'; properties: Record<string, unknown> }
  parse: (input: string) => T
  function: (args: T, thread?: AiThread) => Promise<unknown> | unknown
}

export interface FunctionTool<T> {
  type: 'function'
  function: FunctionDefinition<T>
}
