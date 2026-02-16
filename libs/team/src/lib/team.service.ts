import { Agent, AiThread, Interactor, TeamEvent } from '@coday/model'
import { Team } from './team'
import { TeammateSession, TeammateStatus } from './teammate-session'

export class TeamService {
  private teams: Map<string, Team> = new Map()

  constructor(private readonly interactor: Interactor) {}

  /**
   * Create a new team with the given lead agent name.
   * Returns the created team.
   */
  createTeam(leadAgentName: string): Team {
    const id = `team-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`
    const team = new Team(id, leadAgentName)
    this.teams.set(id, team)
    this.interactor.displayText(`🏗️ Team '${id}' created with lead '${leadAgentName}'`)
    return team
  }

  /**
   * Get a team by ID
   */
  getTeam(teamId: string): Team | undefined {
    return this.teams.get(teamId)
  }

  /**
   * Get the active team for a given lead agent (there should be at most one)
   */
  getTeamByLead(leadAgentName: string): Team | undefined {
    return Array.from(this.teams.values()).find((t) => t.leadAgentName === leadAgentName)
  }

  /**
   * Find any team that has the given agent as a member.
   */
  getTeamByMember(agentName: string): Team | undefined {
    return Array.from(this.teams.values()).find((t) => t.members.has(agentName))
  }

  /**
   * Spawn a teammate into an existing team.
   * Creates a TeammateSession with a forked thread and starts its run loop.
   *
   * @param team - The team to spawn the teammate into
   * @param agent - The agent definition to use
   * @param parentThread - The thread to fork from
   * @param initialTask - The initial task for the teammate
   * @returns The agent name and the TeammateSession
   */
  spawnTeammate(
    team: Team,
    agent: Agent,
    parentThread: AiThread,
    initialTask: string
  ): { instanceName: string; session: TeammateSession } {
    // Check if agent is already in the team
    if (team.members.has(agent.name)) {
      throw new Error(`Agent '${agent.name}' is already a member of this team`)
    }

    const instanceName = agent.name

    // Ensure the agent has TEAM_MEMBER integration enabled
    // This is critical: without this, the toolbox will exclude TEAM_MEMBER tools
    if (!agent.definition.integrations) {
      agent.definition.integrations = {}
    }
    if (!agent.definition.integrations['TEAM_MEMBER']) {
      agent.definition.integrations['TEAM_MEMBER'] = [] // empty array = all tools
    }

    const onStatusChange = (name: string, status: TeammateStatus) => {
      this.interactor.debug(`👤 Teammate '${name}' status: ${status}`)

      // Emit TeamEvent for status change
      this.interactor.sendEvent(
        new TeamEvent({
          teamId: team.id,
          eventType: 'teammate_status_changed',
          teammateName: name,
          status: status,
        })
      )

      if (status === 'idle') {
        // Notify lead via mailbox
        team.mailbox.send(name, team.leadAgentName, `Teammate '${name}' is now idle and available for new tasks.`)
      }
    }

    // Fork a clean thread for the teammate, using instance name for thread identity
    const forkedThread = parentThread.fork(instanceName, true)

    const session = new TeammateSession(
      instanceName,
      agent,
      forkedThread,
      team.mailbox,
      this.interactor,
      onStatusChange
    )

    team.addMember(session)
    session.start(initialTask)

    // Check if there are pre-assigned tasks for this agent and notify them
    const assignedTasks = team.taskList.getTasksForAgent(instanceName)
    if (assignedTasks.length > 0) {
      const taskInfo = assignedTasks.map((t) => `- ${t.id}: ${t.description} [status: ${t.status}]`).join('\n')
      team.mailbox.send(
        team.leadAgentName,
        instanceName,
        `You have ${assignedTasks.length} pre-assigned task(s) in the shared task list. Use listTasks to see all tasks, then use claimTask to claim them:\n${taskInfo}`
      )
    }

    this.interactor.displayText(`👤 Teammate '${instanceName}' spawned with task: ${initialTask.substring(0, 100)}...`)

    // Emit TeamEvent for teammate spawn
    this.interactor.sendEvent(
      new TeamEvent({
        teamId: team.id,
        eventType: 'teammate_spawned',
        teammateName: instanceName,
        status: 'working',
        details: initialTask,
      })
    )

    // Merge forked thread price tracking back to parent
    parentThread.merge(forkedThread)

    return { instanceName, session }
  }

  /**
   * Shutdown a specific teammate
   */
  async shutdownTeammate(team: Team, teammateName: string): Promise<boolean> {
    const member = team.getMember(teammateName)
    if (!member) return false

    await member.shutdown()
    team.members.delete(teammateName)
    this.interactor.displayText(`👋 Teammate '${teammateName}' shut down`)

    // Emit TeamEvent for teammate shutdown
    this.interactor.sendEvent(
      new TeamEvent({
        teamId: team.id,
        eventType: 'teammate_shutdown',
        teammateName: teammateName,
        status: 'stopped',
      })
    )

    return true
  }

  /**
   * Clean up a team entirely — shutdown all members, remove team
   */
  async cleanupTeam(teamId: string): Promise<boolean> {
    const team = this.teams.get(teamId)
    if (!team) return false

    // Check for active teammates
    const activeMembers = Array.from(team.members.values()).filter((m) => m.status !== 'stopped')
    if (activeMembers.length > 0) {
      this.interactor.displayText(`⚠️ Shutting down ${activeMembers.length} active teammate(s) before cleanup...`)
    }

    await team.cleanup()
    this.teams.delete(teamId)
    this.interactor.displayText(`🧹 Team '${teamId}' cleaned up`)
    return true
  }

  /**
   * Clean up all teams (used during application shutdown)
   */
  async cleanupAll(): Promise<void> {
    const teamIds = Array.from(this.teams.keys())
    await Promise.allSettled(teamIds.map((id) => this.cleanupTeam(id)))
  }
}
