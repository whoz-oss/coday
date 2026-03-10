import { ThreadService } from '@coday/service'
import {
  AssistantToolFactory,
  Interactor,
  IntegrationConfig,
  CommandContext,
  CodayTool,
  FunctionTool,
  ThreadUpdateEvent,
} from '@coday/model'
import { MessageEvent } from '@coday/model'

export class ThreadTools extends AssistantToolFactory {
  static readonly TYPE = 'THREAD' as const

  constructor(
    interactor: Interactor,
    private readonly threadService: ThreadService,
    instanceName: string,
    config: IntegrationConfig
  ) {
    super(interactor, instanceName, config)
  }

  protected async buildTools(context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    const result: CodayTool[] = []
    const projectName = context.project.name
    const username = context.username

    // List threads for the current user in the current project
    const listThreadsTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: `${this.name}__list_threads`,
        description:
          'List all threads for the current user in the current project. Returns metadata (id, name, summary, modifiedDate, starring) without message content.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: async () => {
          try {
            const threads = await this.threadService.listThreads(projectName, username)
            if (!threads.length) {
              return 'No threads found.'
            }
            return threads
              .map(
                (t) =>
                  `- id: ${t.id}\n  name: ${t.name || '(unnamed)'}\n  summary: ${t.summary || '(no summary)'}\n  modified: ${t.modifiedDate}\n  starred: ${t.starring?.includes(username) ? 'yes' : 'no'}`
              )
              .join('\n\n')
          } catch (error) {
            const msg = `Failed to list threads: ${error instanceof Error ? error.message : 'Unknown error'}`
            this.interactor.error(msg)
            return msg
          }
        },
      },
    }
    result.push(listThreadsTool)

    // Get the text content (messages only) of a thread
    const getThreadContentTool: FunctionTool<{ threadId: string; lastN?: number }> = {
      type: 'function',
      function: {
        name: `${this.name}__get_thread_content`,
        description:
          'Get the message content (user and assistant messages only, no tool calls) of a specific thread by its id. Useful to query or summarize another thread without switching to it.',
        parameters: {
          type: 'object',
          properties: {
            threadId: {
              type: 'string',
              description: 'The id of the thread to read.',
            },
            lastN: {
              type: 'number',
              description: 'Optional: only return the last N text messages. If omitted, returns all messages.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ threadId, lastN }: { threadId: string; lastN?: number }) => {
          try {
            const thread = await this.threadService.getThread(projectName, threadId)
            if (!thread) {
              return `Thread '${threadId}' not found.`
            }
            if (thread.username !== username) {
              return `Access denied: thread '${threadId}' belongs to another user.`
            }
            const { messages } = await thread.getMessages(undefined, undefined)
            let textMessages = messages
              .filter((m): m is MessageEvent => m instanceof MessageEvent)
              .map((m) => {
                const role = m.role === 'user' ? `[${m.name || username}]` : `[${m.name || 'assistant'}]`
                const text = m.content
                  .filter((c) => c.type === 'text')
                  .map((c) => (c as { type: 'text'; content: string }).content)
                  .join('\n')
                return `${role} ${text}`
              })
            if (lastN && lastN > 0) {
              textMessages = textMessages.slice(-lastN)
            }
            if (!textMessages.length) {
              return 'Thread has no text messages.'
            }
            return textMessages.join('\n\n')
          } catch (error) {
            const msg = `Failed to get thread content: ${error instanceof Error ? error.message : 'Unknown error'}`
            this.interactor.error(msg)
            return msg
          }
        },
      },
    }
    result.push(getThreadContentTool)

    // Update name and/or summary of the current thread (or a specific one)
    const updateThreadTool: FunctionTool<{ name?: string; summary?: string; threadId?: string }> = {
      type: 'function',
      function: {
        name: `${this.name}__update_thread`,
        description:
          'Update the name and/or summary of a thread. Defaults to the current thread if no threadId is provided. Use this to rename or summarize a thread after a conversation.',
        parameters: {
          type: 'object',
          properties: {
            name: {
              type: 'string',
              description: 'New name for the thread. Short, descriptive title of the conversation.',
            },
            summary: {
              type: 'string',
              description: 'Summary of the thread content. 2-4 sentences capturing the main topics and outcomes.',
            },
            threadId: {
              type: 'string',
              description: 'Id of the thread to update. Defaults to the current thread if omitted.',
            },
          },
        },
        parse: JSON.parse,
        function: async ({ name, summary, threadId }: { name?: string; summary?: string; threadId?: string }) => {
          try {
            const targetId = threadId ?? context.aiThread?.id
            if (!targetId) {
              return 'No thread id provided and no current thread available.'
            }
            if (!name && !summary) {
              return 'At least one of name or summary must be provided.'
            }

            const thread = await this.threadService.getThread(projectName, targetId)
            if (!thread) {
              return `Thread '${targetId}' not found.`
            }
            if (thread.username !== username) {
              return `Access denied: thread '${targetId}' belongs to another user.`
            }

            if (name !== undefined) thread.name = name
            if (summary !== undefined) thread.summary = summary

            const repo = this.threadService.getThreadRepository(projectName)
            await repo.save(projectName, thread)

            // Invalidate the list cache so the next list call reloads from disk
            this.threadService.clearCache(projectName)

            // Notify the frontend so it refreshes the thread list
            this.interactor.sendEvent(
              new ThreadUpdateEvent({
                threadId: targetId,
                name: name,
                summary: summary,
              })
            )

            const updated: string[] = []
            if (name !== undefined) updated.push(`name: "${name}"`)
            if (summary !== undefined) updated.push(`summary updated`)
            return `Thread '${targetId}' updated: ${updated.join(', ')}.`
          } catch (error) {
            const msg = `Failed to update thread: ${error instanceof Error ? error.message : 'Unknown error'}`
            this.interactor.error(msg)
            return msg
          }
        },
      },
    }
    result.push(updateThreadTool)

    return result
  }
}
