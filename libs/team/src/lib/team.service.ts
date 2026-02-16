import { Agent, AiThread, Interactor } from '@coday/model'
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
   * Find any team that has the given agent as a member
   */
  getTeamByMember(agentName: string): Team | undefined {
    return Array.from(this.teams.values()).find((t) => t.members.has(agentName))
  }

  /**
   * Spawn a teammate into an existing team.
   * Creates a TeammateSession with a forked thread and starts its run loop.
   */
  spawnTeammate(team: Team, agent: Agent, parentThread: AiThread, initialTask: string): TeammateSession {
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
      if (status === 'idle') {
        // Notify lead via mailbox
        team.mailbox.send(name, team.leadAgentName, `Teammate '${name}' is now idle and available for new tasks.`)
      }
    }

    // Fork a clean thread for the teammate
    const forkedThread = parentThread.fork(agent.name, true)

    const session = new TeammateSession(agent.name, agent, forkedThread, team.mailbox, this.interactor, onStatusChange)

    team.addMember(session)
    session.start(initialTask)

    this.interactor.displayText(`👤 Teammate '${agent.name}' spawned with task: ${initialTask.substring(0, 100)}...`)

    // Merge forked thread price tracking back to parent
    parentThread.merge(forkedThread)

    return session
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
