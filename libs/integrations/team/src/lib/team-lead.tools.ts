import {
  Agent,
  AgentSummary,
  AssistantToolFactory,
  CodayTool,
  CommandContext,
  FunctionTool,
  Interactor,
  TeamEvent,
} from '@coday/model'
import { TeamService } from '@coday/team'

export class TeamLeadTools extends AssistantToolFactory {
  name = 'TEAM_LEAD'

  constructor(
    interactor: Interactor,
    private readonly teamService: TeamService,
    private readonly agentFind: (nameStart: string | undefined, context: CommandContext) => Promise<Agent | undefined>,
    private readonly agentSummaries: () => AgentSummary[]
  ) {
    super(interactor)
  }

  protected async buildTools(context: CommandContext, agentName: string): Promise<CodayTool[]> {
    const tools: CodayTool[] = []

    const agentListText = this.agentSummaries()
      .map((a) => `  - ${a.name} : ${a.description}`)
      .join('\n')

    // --- spawnTeammate ---
    const spawnTeammateFn = async ({ teammateName, task }: { teammateName: string; task: string }) => {
      try {
        // Get or create team for this lead
        let team = this.teamService.getTeamByLead(agentName)
        if (!team) {
          team = this.teamService.createTeam(agentName)
        }

        // Resolve the agent definition
        const agent = await this.agentFind(teammateName, context)
        if (!agent) {
          return `Agent '${teammateName}' not found. Available agents:\n${agentListText}`
        }

        if (!context.aiThread) {
          return 'No active thread to fork from.'
        }

        // Spawn the teammate
        const { instanceName } = this.teamService.spawnTeammate(team, agent, context.aiThread, task)

        return `Teammate '${instanceName}' spawned successfully and is working on: ${task.substring(0, 200)}...`
      } catch (error: any) {
        return `Error spawning teammate: ${error.message}`
      }
    }

    const spawnTeammateTool: FunctionTool<{ teammateName: string; task: string }> = {
      type: 'function',
      function: {
        name: 'spawnTeammate',
        description: `Spawn a new teammate agent into your team. The teammate starts working on the given task immediately in its own isolated thread. When it finishes, it goes idle and waits for messages.

Available agents to spawn:
${agentListText}

Each teammate:
- Has its own context window (does NOT see your conversation history)
- Can use all tools available to it
- Runs independently and in parallel with other teammates
- Goes idle when done and can be messaged for more work
- Notifies you when it becomes idle

NOTE: Each agent can only be spawned once per team. If you need multiple similar agents, create separate agent definition files (e.g., Dev-1.yml, Dev-2.yml, Dev-3.yml).

BEST PRACTICE: Create all tasks FIRST using createTask, then spawn teammates. This ensures agents see their assigned tasks immediately and can properly claim/complete them.

IMPORTANT: Provide a complete, self-contained task description since the teammate has no prior context.`,
        parameters: {
          type: 'object',
          properties: {
            teammateName: {
              type: 'string',
              description: 'Name of the agent to spawn (e.g., "Dev", "Sway", "Dev-1").',
            },
            task: {
              type: 'string',
              description: 'Complete, self-contained task description for the teammate to work on.',
            },
          },
        },
        parse: JSON.parse,
        function: spawnTeammateFn,
      },
    }
    tools.push(spawnTeammateTool)

    // --- sendMessage ---
    const sendMessageFn = async ({ to, message }: { to: string; message: string }) => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team. Spawn teammates first.'

      if (!team.members.has(to)) {
        return `Teammate '${to}' not found. Current teammates: ${team.getMemberNames().join(', ') || 'none'}`
      }

      team.mailbox.send(agentName, to, message)
      return `Message sent to '${to}'.`
    }

    const sendMessageTool: FunctionTool<{ to: string; message: string }> = {
      type: 'function',
      function: {
        name: 'sendTeamMessage',
        description: `Send a message to a specific teammate. If the teammate is idle (waiting for work), this will wake them up and they'll process your message. If they're currently working, the message will be queued and delivered when they finish.

Use this to:
- Assign new tasks to idle teammates
- Provide feedback or corrections
- Share information between teammates
- Ask teammates to coordinate with each other`,
        parameters: {
          type: 'object',
          properties: {
            to: {
              type: 'string',
              description: 'Name of the teammate to send the message to.',
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

    // --- broadcastMessage ---
    const broadcastFn = async ({ message }: { message: string }) => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team. Spawn teammates first.'

      const memberNames = team.getMemberNames()
      if (memberNames.length === 0) return 'No teammates to broadcast to.'

      team.mailbox.broadcast(agentName, message, [...memberNames, agentName])
      return `Message broadcast to ${memberNames.length} teammate(s): ${memberNames.join(', ')}`
    }

    const broadcastTool: FunctionTool<{ message: string }> = {
      type: 'function',
      function: {
        name: 'broadcastTeamMessage',
        description: `Broadcast a message to ALL teammates simultaneously. Use sparingly as it interrupts everyone. Good for announcing conventions, sharing decisions that affect all teammates, or requesting status updates.`,
        parameters: {
          type: 'object',
          properties: {
            message: {
              type: 'string',
              description: 'The message to broadcast to all teammates.',
            },
          },
        },
        parse: JSON.parse,
        function: broadcastFn,
      },
    }
    tools.push(broadcastTool)

    // --- createTask ---
    const createTaskFn = async ({
      description,
      dependencies,
      assignee,
    }: {
      description: string
      dependencies?: string[]
      assignee?: string
    }) => {
      // Get or create team for this lead (allows task creation before spawning)
      let team = this.teamService.getTeamByLead(agentName)
      if (!team) {
        team = this.teamService.createTeam(agentName)
      }

      try {
        const task = team.taskList.createTask(description, dependencies ?? [], assignee)

        // Emit TeamEvent for task creation
        this.interactor.sendEvent(
          new TeamEvent({
            teamId: team.id,
            eventType: 'task_created',
            taskId: task.id,
            taskDescription: task.description,
            taskStatus: task.status,
            teammateName: assignee,
          })
        )

        return `Task created: ${JSON.stringify(task, null, 2)}`
      } catch (error: any) {
        return `Error creating task: ${error.message}`
      }
    }

    const createTaskTool: FunctionTool<{ description: string; dependencies?: string[]; assignee?: string }> = {
      type: 'function',
      function: {
        name: 'createTask',
        description: `Create a task in the shared task list. Can be called BEFORE spawning teammates to set up the full work plan first.

Tasks can have dependencies on other tasks (by task ID) and can be optionally pre-assigned to a specific teammate. When you spawn a teammate who has pre-assigned tasks, they will be notified immediately.

Teammates can see the task list and claim available tasks. A task becomes available when all its dependencies are completed.

BEST PRACTICE: Create all tasks first, then spawn teammates. This ensures proper task lifecycle (claim → complete) and avoids race conditions.

Use this to break down complex work into coordinated steps.`,
        parameters: {
          type: 'object',
          properties: {
            description: {
              type: 'string',
              description: 'Description of the task to create.',
            },
            dependencies: {
              type: 'array',
              items: { type: 'string' },
              description: 'Optional: array of task IDs that must be completed before this task can be claimed.',
            },
            assignee: {
              type: 'string',
              description: 'Optional: name of the teammate to pre-assign this task to.',
            },
          },
        },
        parse: JSON.parse,
        function: createTaskFn,
      },
    }
    tools.push(createTaskTool)

    // --- listTeammates ---
    const listTeammatesFn = async () => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team.'

      if (team.members.size === 0) return 'No teammates in the team.'

      const lines = Array.from(team.members.entries()).map(([name, session]) => {
        const pendingMsgs = team.mailbox.getMessageCount(name)
        return `- ${name}: status=${session.status}, pending_messages=${pendingMsgs}`
      })

      return `Team '${team.id}' (lead: ${team.leadAgentName}):\n${lines.join('\n')}`
    }

    const listTeammatesTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'listTeammates',
        description:
          'List all teammates in your team with their current status (idle, working, stopped) and pending message count.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: listTeammatesFn,
      },
    }
    tools.push(listTeammatesTool)

    // --- listTasks ---
    const listTasksFn = async () => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team.'

      const tasks = team.taskList.listTasks()
      if (tasks.length === 0) return 'No tasks in the task list.'

      return tasks
        .map((t) => {
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
        description: 'List all tasks in the shared task list with their status, assignee, dependencies, and results.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: listTasksFn,
      },
    }
    tools.push(listTasksTool)

    // --- readMessages ---
    const readMessagesFn = async () => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team.'

      const messages = team.mailbox.receive(agentName)
      if (messages.length === 0) return 'No pending messages.'

      return messages.map((m) => `[${m.timestamp}] From ${m.from}: ${m.content}`).join('\n')
    }

    const readMessagesTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'readTeamMessages',
        description:
          'Read and consume all pending messages from teammates. Messages are removed from the queue after reading.',
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: readMessagesFn,
      },
    }
    tools.push(readMessagesTool)

    // --- shutdownTeammate ---
    const shutdownTeammateFn = async ({ teammateName }: { teammateName: string }) => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team.'

      const success = await this.teamService.shutdownTeammate(team, teammateName)
      return success
        ? `Teammate '${teammateName}' has been shut down.`
        : `Teammate '${teammateName}' not found in the team.`
    }

    const shutdownTeammateTool: FunctionTool<{ teammateName: string }> = {
      type: 'function',
      function: {
        name: 'shutdownTeammate',
        description:
          'Shut down a specific teammate. They will finish their current work before stopping. Use this when a teammate is no longer needed.',
        parameters: {
          type: 'object',
          properties: {
            teammateName: {
              type: 'string',
              description: 'Name of the teammate to shut down.',
            },
          },
        },
        parse: JSON.parse,
        function: shutdownTeammateFn,
      },
    }
    tools.push(shutdownTeammateTool)

    // --- cleanupTeam ---
    const cleanupTeamFn = async () => {
      const team = this.teamService.getTeamByLead(agentName)
      if (!team) return 'No active team to clean up.'

      await this.teamService.cleanupTeam(team.id)
      return 'Team has been cleaned up. All teammates shut down and resources released.'
    }

    const cleanupTeamTool: FunctionTool<Record<string, never>> = {
      type: 'function',
      function: {
        name: 'cleanupTeam',
        description:
          "Clean up the entire team. Shuts down all teammates and releases all resources. Use this when the team's work is complete.",
        parameters: {
          type: 'object',
          properties: {},
        },
        parse: JSON.parse,
        function: cleanupTeamFn,
      },
    }
    tools.push(cleanupTeamTool)

    return tools
  }
}
