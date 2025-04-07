import {Interactor, NestedHandler} from '../../../model'
import {CodayServices} from '../../../coday-services'
import {McpListHandler} from './mcp-list.handler'
import {McpEditHandler} from './mcp-edit.handler'
import {McpAddHandler} from './mcp-add.handler'
import {McpDeleteHandler} from './mcp-delete.handler'

/**
 * Handler for MCP server configuration commands
 */
export class McpConfigHandler extends NestedHandler {
  constructor(interactor: Interactor, services: CodayServices) {
    super(
      {
        commandWord: 'mcp',
        description: `Configure MCP (Model Context Protocol) servers`,
      },
      interactor
    )

    this.handlers = [
      new McpListHandler(interactor, services.mcp),
      new McpEditHandler(interactor, services.mcp),
      new McpAddHandler(interactor, services.mcp),
      new McpDeleteHandler(interactor, services.mcp),
    ]
  }
}
