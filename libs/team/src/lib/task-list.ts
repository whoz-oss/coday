export type TaskStatus = 'pending' | 'in_progress' | 'completed'

export interface Task {
  id: string
  description: string
  status: TaskStatus
  assignee?: string
  dependencies: string[] // task IDs that must complete first
  result?: string // completion result/summary
}

export class TaskList {
  private tasks: Map<string, Task> = new Map()
  private nextId: number = 1

  /**
   * Create a new task. Returns the created task.
   * Dependencies are validated — all referenced task IDs must exist.
   */
  createTask(description: string, dependencies: string[] = [], assignee?: string): Task {
    // Validate dependencies exist
    for (const depId of dependencies) {
      if (!this.tasks.has(depId)) {
        throw new Error(`Dependency task '${depId}' does not exist`)
      }
    }
    const id = `task-${this.nextId++}`
    const task: Task = { id, description, status: 'pending', dependencies, assignee }
    this.tasks.set(id, task)
    return { ...task }
  }

  /**
   * Claim a task for an agent. Returns true if successfully claimed.
   * A task can only be claimed if:
   * - It exists
   * - It's in 'pending' status
   * - All its dependencies are 'completed'
   * - It's either unassigned or assigned to the claiming agent
   */
  claimTask(taskId: string, agentName: string): boolean {
    const task = this.tasks.get(taskId)
    if (!task) return false
    if (task.status !== 'pending') return false
    if (task.assignee && task.assignee !== agentName) return false
    if (!this.areDependenciesResolved(taskId)) return false

    task.status = 'in_progress'
    task.assignee = agentName
    return true
  }

  /**
   * Mark a task as completed with an optional result summary.
   * Only the assigned agent can complete a task, and it must be in_progress.
   */
  completeTask(taskId: string, agentName: string, result?: string): boolean {
    const task = this.tasks.get(taskId)
    if (!task) return false
    if (task.status !== 'in_progress') return false
    if (task.assignee !== agentName) return false

    task.status = 'completed'
    task.result = result
    return true
  }

  /**
   * Get all tasks that are available to claim (pending + dependencies resolved)
   */
  getAvailableTasks(): Task[] {
    return Array.from(this.tasks.values())
      .filter((t) => t.status === 'pending' && this.areDependenciesResolved(t.id))
      .map((t) => ({ ...t }))
  }

  /**
   * Get all tasks, returning copies
   */
  listTasks(): Task[] {
    return Array.from(this.tasks.values()).map((t) => ({ ...t }))
  }

  /**
   * Get a specific task by ID
   */
  getTask(taskId: string): Task | undefined {
    const task = this.tasks.get(taskId)
    return task ? { ...task } : undefined
  }

  /**
   * Check if all tasks are completed
   */
  isAllCompleted(): boolean {
    return Array.from(this.tasks.values()).every((t) => t.status === 'completed')
  }

  /**
   * Get tasks assigned to a specific agent
   */
  getTasksForAgent(agentName: string): Task[] {
    return Array.from(this.tasks.values())
      .filter((t) => t.assignee === agentName)
      .map((t) => ({ ...t }))
  }

  /**
   * Check if all dependencies of a task are completed
   */
  private areDependenciesResolved(taskId: string): boolean {
    const task = this.tasks.get(taskId)
    if (!task) return false
    return task.dependencies.every((depId) => {
      const dep = this.tasks.get(depId)
      return dep?.status === 'completed'
    })
  }
}
