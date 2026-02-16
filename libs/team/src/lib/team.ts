import { TaskList } from './task-list'
import { Mailbox } from './mailbox'
import { TeammateSession } from './teammate-session'

export class Team {
  readonly taskList: TaskList = new TaskList()
  readonly mailbox: Mailbox = new Mailbox()
  readonly members: Map<string, TeammateSession> = new Map()

  constructor(
    readonly id: string,
    readonly leadAgentName: string
  ) {}

  addMember(session: TeammateSession): void {
    if (this.members.has(session.name)) {
      throw new Error(`Teammate '${session.name}' already exists in team '${this.id}'`)
    }
    this.members.set(session.name, session)
  }

  getMember(name: string): TeammateSession | undefined {
    return this.members.get(name)
  }

  getMemberNames(): string[] {
    return Array.from(this.members.keys())
  }

  /**
   * Shutdown all teammates and clean up resources
   */
  async cleanup(): Promise<void> {
    // Cancel all mailbox waiters first to unblock any waiting teammates
    this.mailbox.cancelAllWaiters()

    // Shutdown all members in parallel
    await Promise.allSettled(Array.from(this.members.values()).map((member) => member.shutdown()))

    this.members.clear()
  }
}
