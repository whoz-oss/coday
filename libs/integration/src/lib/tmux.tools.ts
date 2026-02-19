import { AssistantToolFactory, Interactor } from '@coday/model'
import { CodayTool } from '@coday/model'
import { CommandContext } from '@coday/model'
import { execSync } from 'child_process'

export class TmuxTools extends AssistantToolFactory {
  name = 'TMUX'

  constructor(interactor: Interactor) {
    super(interactor)
  }

  protected async buildTools(_context: CommandContext, _agentName: string): Promise<CodayTool[]> {
    return [
      {
        type: 'function',
        function: {
          name: 'tmux',
          description: `Manage long-running processes in persistent tmux sessions.
Sessions survive across agent interactions and can be inspected at any time.

Actions:
- list: show all active tmux sessions
- status <session>: check if a specific session is running ("running" or "stopped")
- start <session> <command>: launch a command in a named session (creates session if needed)
- logs <session>: read recent output from a session (last ~200 lines)
- send <session> <command>: send a command to an already running session
- stop <session>: kill a session and its processes

Use clear, stable session names matching the application role: "backend", "frontend", "agentos", "worker", "db".`,
          parameters: {
            type: 'object',
            properties: {
              action: {
                type: 'string',
                enum: ['list', 'status', 'start', 'logs', 'send', 'stop'],
                description: 'The tmux action to perform.',
              },
              session: {
                type: 'string',
                description: 'Session name (required for all actions except list).',
              },
              command: {
                type: 'string',
                description: 'Command to run (required for start and send actions).',
              },
            },
          },
          parse: JSON.parse,
          function: async ({ action, session, command }: { action: string; session?: string; command?: string }) => {
            try {
              switch (action) {
                case 'list': {
                  try {
                    return execSync('tmux list-sessions', { encoding: 'utf8' }).trim()
                  } catch {
                    return 'No tmux sessions running'
                  }
                }

                case 'status': {
                  if (!session) return 'Error: session name is required for status'
                  try {
                    execSync(`tmux has-session -t ${session}`, { encoding: 'utf8' })
                    return 'running'
                  } catch {
                    return 'stopped'
                  }
                }

                case 'start': {
                  if (!session) return 'Error: session name is required for start'
                  if (!command) return 'Error: command is required for start'
                  try {
                    execSync(`tmux new-session -d -s ${session} -x 220 -y 50`, { encoding: 'utf8' })
                  } catch {
                    // Session already exists, that's fine
                  }
                  execSync(`tmux send-keys -t ${session} ${JSON.stringify(command)} Enter`, { encoding: 'utf8' })
                  return `Session '${session}' started`
                }

                case 'logs': {
                  if (!session) return 'Error: session name is required for logs'
                  try {
                    return execSync(`tmux capture-pane -t ${session} -p -S -200`, { encoding: 'utf8' }).trim()
                  } catch {
                    return `Session '${session}' not found`
                  }
                }

                case 'send': {
                  if (!session) return 'Error: session name is required for send'
                  if (!command) return 'Error: command is required for send'
                  try {
                    execSync(`tmux send-keys -t ${session} ${JSON.stringify(command)} Enter`, { encoding: 'utf8' })
                    return `Command sent to session '${session}'`
                  } catch {
                    return `Session '${session}' not found`
                  }
                }

                case 'stop': {
                  if (!session) return 'Error: session name is required for stop'
                  try {
                    execSync(`tmux kill-session -t ${session}`, { encoding: 'utf8' })
                    return `Session '${session}' killed`
                  } catch {
                    return `Session '${session}' not found`
                  }
                }

                default:
                  return `Unknown action: ${action}`
              }
            } catch (error) {
              const message = `tmux error: ${error instanceof Error ? error.message : String(error)}`
              this.interactor.error(message)
              return message
            }
          },
        },
      },
    ]
  }

  override async kill(): Promise<void> {
    // No cleanup needed
  }
}
