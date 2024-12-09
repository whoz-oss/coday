import { CodayTool } from './assistant-tool-factory'
import { ToolRequestEvent, ToolResponseEvent } from '../shared'

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

    try {
      // Parse args from the request
      const args = JSON.parse(toolRequest.args)
      // Make sure args is always an array
      const argsArray = Array.isArray(args) ? args : [args]
      output = await toolFunc(...argsArray)
    } catch (err) {
      throw new Error(`Error executing tool '${toolRequest.name}': ${JSON.stringify(err)}`)
    }

    if (!output) {
      output = `Tool function ${toolRequest.name} finished without error.`
    }

    if (typeof output !== 'string') {
      output = JSON.stringify(output)
    }

    return new ToolResponseEvent({
      toolRequestId: toolRequest.toolRequestId,
      output,
    })
  }

  // /**
  //  * Observable version of runTool (experimental)
  //  * This could be used for long-running tasks where we want to:
  //  * - Emit immediate acknowledgment
  //  * - Stream progress updates
  //  * - Complete with final result
  //  *
  //  * @param toolRequest The tool request containing name and arguments
  //  */
  // runToolAsObservable(toolRequest: ToolRequestEvent): Observable<any> {
  //   return defer(() => {
  //     // For now, just wrap the Promise in an Observable
  //     return from(this.runTool(toolRequest))
  //
  //     // Future enhancement could look like:
  //     /*
  //     return new Observable(subscriber => {
  //       subscriber.next({ status: 'started', message: `Tool ${toolRequest.name} execution started` })
  //
  //       this.runTool(toolRequest)
  //         .then(result => {
  //           subscriber.next({ status: 'completed', result })
  //           subscriber.complete()
  //         })
  //         .catch(error => subscriber.error(error))
  //
  //       // Return cleanup function if needed
  //       return () => {
  //         // Cancel operation if possible
  //       }
  //     })
  //     */
  //   })
  // }
}
