import { AssistantToolFactory, CodayTool, CommandContext, FunctionTool, Interactor, TeamEvent } from '@coday/model'
import { MailboxMessage, Task, TeamService } from '@coday/team'

export class TeamMemberTools extends AssistantToolFactory {
  name = 'TEAM_MEMBER'

  constructor(
    interactor: Interactor,
    private readonly teamService: TeamService
  ) {
    super(interactor)
  }

  protected async buildTools(_context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const tools: CodayTool[] = []

    // Capture teamService for use in closures (avoids 'this' shadowing issues)
    const teamService = this.teamService

    // --- sendMessage ---
    const sendMessageFn = async ({ to, message }: { to: string; message: string }) => {
      const team = teamService.getTeamByMember(agentName)
      if (!team) return 'You are not part of a team.'

      // Can send to lead or other members
      const validRecipients = [...team.getMemberNames(), team.leadAgentName]
      if (!validRecipients.includes(to)) {
        return `Recipient '${to}' not found. Available: ${validRecipients.join(', ')}`
      }

      team.mailbox.send(agentName, to, message)
      return `Message sent to '${to}'.`
    }

    const sendMessageTool: FunctionTool<{ to: string; message: string }> = {
      type: 'function',
      function: {
        name: 'sendTeamMessage',
        description: `Send a message to another teammate or the team lead. Use this to:
- Report progress or completion
- Ask questions or request clarification
- Share findings with other teammates
- Request help or coordination`,
        parameters: {
          type: 'object',
          properties: {
            to: {
              type: 'string',
              description: 'Name of the recipient (teammate or team lead).',
            },
            message: {
              type: 'string',
              description: 'The message content to send.',
            },
          },
        },
        parse: JSON.parse,
        function: sendMessageFn,
      },
    }
    tools.push(sendMessageTool)

    // --- readMessages ---
    const readMessagesFn = async () => {
      const team = teamService.getTeamByMember(agentName)
      if (!team) return 'You are not part of a team.'

      const messages = team.mailbox.receive(agentName)
      if (messages.length === 0) return 'No pending messages.'

      return messages.map((m: MailboxMessage) => `[${m.timestamp}] From ${m.from}: ${m.content}`).join('\n')
    }

    const readMessagesTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'readTeamMessages',
        description: 'Read and consume all pending messages from other teammates or the lead.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: readMessagesFn,
      },
    }
    tools.push(readMessagesTool)

    // --- claimTask ---
    const claimTaskFn = async ({ taskId }: { taskId: string }) => {
      const team = teamService.getTeamByMember(agentName)
      if (!team) return 'You are not part of a team.'

      const success = team.taskList.claimTask(taskId, agentName)
      if (success) {
        const task = team.taskList.getTask(taskId)

        // Emit TeamEvent for task status change
        this.interactor.sendEvent(
          new TeamEvent({
            teamId: team.id,
            eventType: 'task_status_changed',
            taskId: taskId,
            taskDescription: task?.description,
            taskStatus: 'in_progress',
            teammateName: agentName,
          })
        )

        return `Task '${taskId}' claimed successfully. Description: ${task?.description ?? 'unknown'}`
      }

      const task = team.taskList.getTask(taskId)
      if (!task) return `Task '${taskId}' does not exist.`
      if (task.status !== 'pending') return `Task '${taskId}' is already ${task.status}.`
      if (task.assignee && task.assignee !== agentName) return `Task '${taskId}' is assigned to '${task.assignee}'.`
      return `Task '${taskId}' cannot be claimed — dependencies may not be resolved yet.`
    }

    const claimTaskTool: FunctionTool<{ taskId: string }> = {
      type: 'function',
      function: {
        name: 'claimTask',
        description: `Claim a task from the shared task list. A task can only be claimed if it's pending, its dependencies are all completed, and it's either unassigned or assigned to you. Use listTasks first to see available tasks.`,
        parameters: {
          type: 'object',
          properties: {
            taskId: {
              type: 'string',
              description: 'ID of the task to claim (e.g., "task-1").',
            },
          },
        },
        parse: JSON.parse,
        function: claimTaskFn,
      },
    }
    tools.push(claimTaskTool)

    // --- completeTask ---
    const completeTaskFn = async ({ taskId, result }: { taskId: string; result?: string }) => {
      const team = teamService.getTeamByMember(agentName)
      if (!team) return 'You are not part of a team.'

      const success = team.taskList.completeTask(taskId, agentName, result)
      if (success) {
        const task = team.taskList.getTask(taskId)

        // Emit TeamEvent for task completion
        this.interactor.sendEvent(
          new TeamEvent({
            teamId: team.id,
            eventType: 'task_status_changed',
            taskId: taskId,
            taskDescription: task?.description,
            taskStatus: 'completed',
            teammateName: agentName,
            details: result,
          })
        )

        // Notify lead about task completion
        team.mailbox.send(
          agentName,
          team.leadAgentName,
          `Task '${taskId}' completed.${result ? ` Result: ${result}` : ''}`
        )

        // Check if any tasks just became available and notify their assignees
        const availableTasks = team.taskList.getAvailableTasks()
        for (const availTask of availableTasks) {
          if (availTask.assignee && team.members.has(availTask.assignee)) {
            team.mailbox.send(
              agentName,
              availTask.assignee,
              `Task '${availTask.id}' is now available (dependencies resolved): ${availTask.description}`
            )
          }
        }

        return `Task '${taskId}' marked as completed.`
      }

      const task = team.taskList.getTask(taskId)
      if (!task) return `Task '${taskId}' does not exist.`
      if (task.assignee !== agentName) return `Task '${taskId}' is not assigned to you.`
      return `Task '${taskId}' is in status '${task.status}' and cannot be completed.`
    }

    const completeTaskTool: FunctionTool<{ taskId: string; result?: string }> = {
      type: 'function',
      function: {
        name: 'completeTask',
        description: `Mark a task as completed. You can only complete tasks that are assigned to you and in 'in_progress' status. Optionally provide a result summary. This will automatically notify the lead and unblock any dependent tasks.`,
        parameters: {
          type: 'object',
          properties: {
            taskId: {
              type: 'string',
              description: 'ID of the task to complete.',
            },
            result: {
              type: 'string',
              description: 'Optional: summary of the work done or result of the task.',
            },
          },
        },
        parse: JSON.parse,
        function: completeTaskFn,
      },
    }
    tools.push(completeTaskTool)

    // --- listTasks ---
    const listTasksFn = async () => {
      const team = teamService.getTeamByMember(agentName)
      if (!team) return 'You are not part of a team.'

      const tasks = team.taskList.listTasks()
      if (tasks.length === 0) return 'No tasks in the task list.'

      return tasks
        .map((t: Task) => {
          const deps = t.dependencies.length > 0 ? ` (depends on: ${t.dependencies.join(', ')})` : ''
          const assigneeStr = t.assignee ? ` [assigned: ${t.assignee}]` : ''
          const resultStr = t.result ? ` → ${t.result.substring(0, 100)}` : ''
          return `- ${t.id}: [${t.status}]${assigneeStr} ${t.description}${deps}${resultStr}`
        })
        .join('\n')
    }

    const listTasksTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'listTasks',
        description:
          'List all tasks in the shared task list with their status, assignee, dependencies, and results. Use this to find available tasks to claim.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: listTasksFn,
      },
    }
    tools.push(listTasksTool)

    // --- listTeammates ---
    const listTeammatesFn = async () => {
      const team = teamService.getTeamByMember(agentName)
      if (!team) return 'You are not part of a team.'

      const lines: string[] = []
      lines.push(`Lead: ${team.leadAgentName}`)

      for (const [name, session] of team.members) {
        const marker = name === agentName ? ' (you)' : ''
        lines.push(`- ${name}: status=${session.status}${marker}`)
      }

      return lines.join('\n')
    }

    const listTeammatesTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'listTeammates',
        description: 'List all teammates in the team with their status, including the team lead.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: listTeammatesFn,
      },
    }
    tools.push(listTeammatesTool)

    return tools
  }
}
