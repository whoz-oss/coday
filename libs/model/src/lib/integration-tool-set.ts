import { ToolRequestEvent, ToolResponseEvent } from './coday-events'
import { CodayTool } from './coday-tool'

export class ToolSet {
  readonly charLength: number = 0

  constructor(private readonly tools: CodayTool[]) {
    this.charLength = JSON.stringify(tools).length
  }

  getTools(): CodayTool[] {
    return [...this.tools]
  }

  /**
   * Runs a tool based on the given tool request
   * For now returns a Promise, but could be enhanced to return an Observable
   * for long-running tasks that need intermediate status updates
   *
   * @param toolRequest The tool request containing name and arguments
   * @returns Promise of the tool execution result
   * @throws Error if tool not found or execution fails
   */
  async run(toolRequest: ToolRequestEvent): Promise<ToolResponseEvent> {
    const tool = this.tools.find((tool) => tool.function.name === toolRequest.name)
    if (!tool) {
      throw new Error(`Tool '${toolRequest.name}' not found`)
    }

    let output: any
    const toolFunc = tool.function.function as (...args: any[]) => Promise<any>

    // Parse args from the request
    const args = JSON.parse(toolRequest.args)
    // Make sure args is always an array
    const argsArray = Array.isArray(args) ? args : [args]
    output = await toolFunc(...argsArray)

    if (!output) {
      output = `Tool function ${toolRequest.name} finished without error.`
    }

    // Handle rich content (MessageContent) or string output
    if (typeof output !== 'string') {
      // Check if it's MessageContent format
      if (
        output &&
        typeof output === 'object' &&
        'type' in output &&
        (output.type === 'text' || output.type === 'image')
      ) {
        // Pass through MessageContent directly
        return toolRequest.buildResponse(output)
      } else {
        // Fallback to JSON stringify for other object types
        output = JSON.stringify(output)
      }
    }

    return toolRequest.buildResponse(output)
  }
}
