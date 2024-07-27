export interface FunctionDefinition<T> {
  name: string,
  description: string,
  // TODO: have a JSON schema type with parameters a single object
  parameters: Record<string, unknown>,
  parse: (input: string) => T
  // TODO: check this typing is correct and appropriate
  function: (args: T) => Promise<unknown> | unknown
}

export interface FunctionTool<T> {
  type: "function"
  function: FunctionDefinition<T>
}