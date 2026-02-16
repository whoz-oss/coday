import { TaskList } from './task-list'

describe('TaskList', () => {
  let taskList: TaskList

  beforeEach(() => {
    taskList = new TaskList()
  })

  describe('createTask', () => {
    it('should create a task without dependencies', () => {
      const task = taskList.createTask('Task 1')

      expect(task.id).toBe('task-1')
      expect(task.description).toBe('Task 1')
      expect(task.status).toBe('pending')
      expect(task.dependencies).toEqual([])
      expect(task.assignee).toBeUndefined()
    })

    it('should create a task with an assignee', () => {
      const task = taskList.createTask('Task 1', [], 'agent-1')

      expect(task.assignee).toBe('agent-1')
    })

    it('should create a task with valid dependencies', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2', [task1.id])

      expect(task2.dependencies).toEqual([task1.id])
    })

    it('should throw error for invalid dependency IDs', () => {
      expect(() => {
        taskList.createTask('Task 1', ['non-existent-id'])
      }).toThrow("Dependency task 'non-existent-id' does not exist")
    })

    it('should auto-increment task IDs', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2')
      const task3 = taskList.createTask('Task 3')

      expect(task1.id).toBe('task-1')
      expect(task2.id).toBe('task-2')
      expect(task3.id).toBe('task-3')
    })
  })

  describe('claimTask', () => {
    it('should successfully claim a pending task with resolved dependencies', () => {
      const task = taskList.createTask('Task 1')
      const claimed = taskList.claimTask(task.id, 'agent-1')

      expect(claimed).toBe(true)
      const retrieved = taskList.getTask(task.id)
      expect(retrieved?.status).toBe('in_progress')
      expect(retrieved?.assignee).toBe('agent-1')
    })

    it('should fail to claim a non-existent task', () => {
      const claimed = taskList.claimTask('non-existent', 'agent-1')
      expect(claimed).toBe(false)
    })

    it('should fail to claim an in_progress task', () => {
      const task = taskList.createTask('Task 1')
      taskList.claimTask(task.id, 'agent-1')

      const claimed = taskList.claimTask(task.id, 'agent-2')
      expect(claimed).toBe(false)
    })

    it('should fail to claim a completed task', () => {
      const task = taskList.createTask('Task 1')
      taskList.claimTask(task.id, 'agent-1')
      taskList.completeTask(task.id, 'agent-1')

      const claimed = taskList.claimTask(task.id, 'agent-2')
      expect(claimed).toBe(false)
    })

    it('should fail to claim a task with unresolved dependencies', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2', [task1.id])

      const claimed = taskList.claimTask(task2.id, 'agent-1')
      expect(claimed).toBe(false)
    })

    it('should succeed to claim a task after dependencies are completed', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2', [task1.id])

      taskList.claimTask(task1.id, 'agent-1')
      taskList.completeTask(task1.id, 'agent-1')

      const claimed = taskList.claimTask(task2.id, 'agent-2')
      expect(claimed).toBe(true)
    })

    it('should fail to claim a task assigned to another agent', () => {
      const task = taskList.createTask('Task 1', [], 'agent-1')

      const claimed = taskList.claimTask(task.id, 'agent-2')
      expect(claimed).toBe(false)
    })

    it('should succeed to claim a task assigned to the claiming agent', () => {
      const task = taskList.createTask('Task 1', [], 'agent-1')

      const claimed = taskList.claimTask(task.id, 'agent-1')
      expect(claimed).toBe(true)
    })
  })

  describe('completeTask', () => {
    it('should successfully complete an in_progress task', () => {
      const task = taskList.createTask('Task 1')
      taskList.claimTask(task.id, 'agent-1')

      const completed = taskList.completeTask(task.id, 'agent-1', 'Task completed successfully')

      expect(completed).toBe(true)
      const retrieved = taskList.getTask(task.id)
      expect(retrieved?.status).toBe('completed')
      expect(retrieved?.result).toBe('Task completed successfully')
    })

    it('should fail to complete a task by wrong agent', () => {
      const task = taskList.createTask('Task 1')
      taskList.claimTask(task.id, 'agent-1')

      const completed = taskList.completeTask(task.id, 'agent-2')
      expect(completed).toBe(false)
    })

    it('should fail to complete a pending task', () => {
      const task = taskList.createTask('Task 1')

      const completed = taskList.completeTask(task.id, 'agent-1')
      expect(completed).toBe(false)
    })

    it('should fail to complete a non-existent task', () => {
      const completed = taskList.completeTask('non-existent', 'agent-1')
      expect(completed).toBe(false)
    })
  })

  describe('getAvailableTasks', () => {
    it('should return only pending tasks with resolved dependencies', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2', [task1.id])
      const task3 = taskList.createTask('Task 3')

      let available = taskList.getAvailableTasks()
      expect(available).toHaveLength(2)
      expect(available.map((t) => t.id)).toEqual([task1.id, task3.id])

      taskList.claimTask(task1.id, 'agent-1')
      taskList.completeTask(task1.id, 'agent-1')

      available = taskList.getAvailableTasks()
      expect(available).toHaveLength(2)
      expect(available.map((t) => t.id)).toEqual([task2.id, task3.id])
    })

    it('should return empty array when all tasks are completed or in progress', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2')

      taskList.claimTask(task1.id, 'agent-1')
      taskList.claimTask(task2.id, 'agent-2')

      const available = taskList.getAvailableTasks()
      expect(available).toHaveLength(0)
    })
  })

  describe('listTasks', () => {
    it('should return all tasks as copies', () => {
      taskList.createTask('Task 1')
      taskList.createTask('Task 2')

      const tasks = taskList.listTasks()
      expect(tasks).toHaveLength(2)

      // Modify returned task should not affect internal state
      tasks[0]!.status = 'completed'
      const retrieved = taskList.getTask(tasks[0]!.id)
      expect(retrieved?.status).toBe('pending')
    })

    it('should return empty array when no tasks exist', () => {
      const tasks = taskList.listTasks()
      expect(tasks).toEqual([])
    })
  })

  describe('getTask', () => {
    it('should return a copy of the task', () => {
      const task = taskList.createTask('Task 1')
      const retrieved = taskList.getTask(task.id)

      expect(retrieved).toEqual(task)
      expect(retrieved).not.toBe(task) // Different object reference
    })

    it('should return undefined for non-existent task', () => {
      const retrieved = taskList.getTask('non-existent')
      expect(retrieved).toBeUndefined()
    })
  })

  describe('isAllCompleted', () => {
    it('should return true when all tasks are completed', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2')

      taskList.claimTask(task1.id, 'agent-1')
      taskList.claimTask(task2.id, 'agent-2')
      taskList.completeTask(task1.id, 'agent-1')
      taskList.completeTask(task2.id, 'agent-2')

      expect(taskList.isAllCompleted()).toBe(true)
    })

    it('should return false when some tasks are pending or in progress', () => {
      const task1 = taskList.createTask('Task 1')
      taskList.createTask('Task 2')

      taskList.claimTask(task1.id, 'agent-1')

      expect(taskList.isAllCompleted()).toBe(false)
    })

    it('should return true when there are no tasks', () => {
      expect(taskList.isAllCompleted()).toBe(true)
    })
  })

  describe('getTasksForAgent', () => {
    it('should return only tasks assigned to the agent', () => {
      const task1 = taskList.createTask('Task 1')
      const task2 = taskList.createTask('Task 2')
      const task3 = taskList.createTask('Task 3')

      taskList.claimTask(task1.id, 'agent-1')
      taskList.claimTask(task2.id, 'agent-2')
      taskList.claimTask(task3.id, 'agent-1')

      const agent1Tasks = taskList.getTasksForAgent('agent-1')
      expect(agent1Tasks).toHaveLength(2)
      expect(agent1Tasks.map((t) => t.id)).toEqual([task1.id, task3.id])

      const agent2Tasks = taskList.getTasksForAgent('agent-2')
      expect(agent2Tasks).toHaveLength(1)
      expect(agent2Tasks[0]!.id).toBe(task2.id)
    })

    it('should return empty array for agent with no tasks', () => {
      taskList.createTask('Task 1')
      const tasks = taskList.getTasksForAgent('agent-1')
      expect(tasks).toEqual([])
    })
  })

  describe('tasks-before-agents scenarios', () => {
    it('should allow tasks to be created before any agents exist', () => {
      // This simulates creating a full task list before spawning any agents
      const task1 = taskList.createTask('Research component A', [], 'Dev-1')
      const task2 = taskList.createTask('Research component B', [], 'Dev-2')
      const task3 = taskList.createTask('Synthesize findings', [task1.id, task2.id], 'Archay')

      // All tasks should be created successfully
      expect(task1.id).toBe('task-1')
      expect(task2.id).toBe('task-2')
      expect(task3.id).toBe('task-3')

      // Task 1 and 2 should be available (no dependencies)
      const available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id).sort()).toEqual([task1.id, task2.id].sort())

      // Task 3 should be blocked by dependencies
      expect(available.find((t) => t.id === task3.id)).toBeUndefined()
    })

    it('should support task assignment to agents that do not exist yet', () => {
      // Create tasks assigned to agents that haven't been spawned
      const task1 = taskList.createTask('Task for future agent', [], 'AgentThatDoesNotExistYet')

      expect(task1.assignee).toBe('AgentThatDoesNotExistYet')
      expect(task1.status).toBe('pending')

      // When the agent eventually spawns and tries to claim, it should work
      const claimed = taskList.claimTask(task1.id, 'AgentThatDoesNotExistYet')
      expect(claimed).toBe(true)

      const retrieved = taskList.getTask(task1.id)
      expect(retrieved?.status).toBe('in_progress')
      expect(retrieved?.assignee).toBe('AgentThatDoesNotExistYet')
    })

    it('should support pre-assigned tasks with dependencies', () => {
      // Create a dependency chain with pre-assigned agents
      const task1 = taskList.createTask('First task', [], 'Dev-1')
      const task2 = taskList.createTask('Second task', [task1.id], 'Dev-2')
      const task3 = taskList.createTask('Third task', [task2.id], 'Dev-3')

      // Dev-1 can claim immediately
      expect(taskList.claimTask(task1.id, 'Dev-1')).toBe(true)

      // Dev-2 cannot claim yet (dependencies not resolved)
      expect(taskList.claimTask(task2.id, 'Dev-2')).toBe(false)

      // Complete task 1
      taskList.completeTask(task1.id, 'Dev-1')

      // Now Dev-2 can claim
      expect(taskList.claimTask(task2.id, 'Dev-2')).toBe(true)

      // Dev-3 still cannot claim
      expect(taskList.claimTask(task3.id, 'Dev-3')).toBe(false)

      // Complete task 2
      taskList.completeTask(task2.id, 'Dev-2')

      // Now Dev-3 can claim
      expect(taskList.claimTask(task3.id, 'Dev-3')).toBe(true)
    })

    it('should return correct tasks for pre-assigned agents', () => {
      // Create multiple tasks with various assignments
      const task1 = taskList.createTask('Dev-1 task A', [], 'Dev-1')
      const task2 = taskList.createTask('Dev-2 task', [], 'Dev-2')
      const task3 = taskList.createTask('Dev-1 task B', [], 'Dev-1')
      const task4 = taskList.createTask('Unassigned task', [])

      // Check Dev-1's pre-assigned tasks
      const dev1Tasks = taskList.getTasksForAgent('Dev-1')
      expect(dev1Tasks).toHaveLength(2)
      expect(dev1Tasks.map((t) => t.id).sort()).toEqual([task1.id, task3.id].sort())

      // Check Dev-2's pre-assigned tasks
      const dev2Tasks = taskList.getTasksForAgent('Dev-2')
      expect(dev2Tasks).toHaveLength(1)
      expect(dev2Tasks[0]!.id).toBe(task2.id)

      // Unassigned task should not appear in any agent's list
      expect(dev1Tasks.find((t) => t.id === task4.id)).toBeUndefined()
      expect(dev2Tasks.find((t) => t.id === task4.id)).toBeUndefined()
    })
  })

  describe('dependency chain', () => {
    it('should properly handle A → B → C dependency chain', () => {
      const taskA = taskList.createTask('Task A')
      const taskB = taskList.createTask('Task B', [taskA.id])
      const taskC = taskList.createTask('Task C', [taskB.id])

      // Initially, only A is available
      let available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id)).toEqual([taskA.id])

      // Complete A, now B is available
      taskList.claimTask(taskA.id, 'agent-1')
      taskList.completeTask(taskA.id, 'agent-1')

      available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id)).toEqual([taskB.id])

      // Complete B, now C is available
      taskList.claimTask(taskB.id, 'agent-2')
      taskList.completeTask(taskB.id, 'agent-2')

      available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id)).toEqual([taskC.id])

      // Complete C
      taskList.claimTask(taskC.id, 'agent-3')
      taskList.completeTask(taskC.id, 'agent-3')

      expect(taskList.isAllCompleted()).toBe(true)
    })

    it('should handle multiple dependencies (diamond pattern)', () => {
      const taskA = taskList.createTask('Task A')
      const taskB = taskList.createTask('Task B', [taskA.id])
      const taskC = taskList.createTask('Task C', [taskA.id])
      const taskD = taskList.createTask('Task D', [taskB.id, taskC.id])

      // Complete A, B and C are available
      taskList.claimTask(taskA.id, 'agent-1')
      taskList.completeTask(taskA.id, 'agent-1')

      let available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id).sort()).toEqual([taskB.id, taskC.id].sort())

      // Complete B, but D is still blocked by C
      taskList.claimTask(taskB.id, 'agent-2')
      taskList.completeTask(taskB.id, 'agent-2')

      available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id)).toEqual([taskC.id])

      // Complete C, now D is available
      taskList.claimTask(taskC.id, 'agent-3')
      taskList.completeTask(taskC.id, 'agent-3')

      available = taskList.getAvailableTasks()
      expect(available.map((t) => t.id)).toEqual([taskD.id])
    })
  })
})
