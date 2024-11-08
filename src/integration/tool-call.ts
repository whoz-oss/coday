export interface ToolCall {
  id?: string
  name: string
  args: string
}

export interface ToolResponse {
  id?: string
  name: string
  response: string
}