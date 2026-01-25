import { CodayServices } from '@coday/coday-services'
import { McpListHandler } from './mcp-list.handler'
import { McpEditHandler } from './mcp-edit.handler'
import { McpAddHandler } from './mcp-add.handler'
import { McpDeleteHandler } from './mcp-delete.handler'
import { NestedHandler } from '@coday/handler'
import { Interactor } from '@coday/model/interactor'

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

    // Create edit handler first so it can be referenced by add handler
    const editHandler = new McpEditHandler(interactor, services.mcp)

    this.handlers = [
      new McpListHandler(interactor, services.mcp),
      editHandler,
      new McpAddHandler(interactor, services.mcp, editHandler),
      new McpDeleteHandler(interactor, services.mcp),
    ]
  }
}
