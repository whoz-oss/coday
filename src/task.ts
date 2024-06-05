export type Task = {
    command: string
    aiId?: string // absent = use current or default, present = use defined one
    threadId?: string | null // absent = use current or default, present falsy = need a new one, present truthy = use it
}

export type NestableTask = Task | Task[]