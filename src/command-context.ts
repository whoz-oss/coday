type Task = {
    description: string
    key: string
    data?: any
}

type History = {
    command: string
    response: string
}

export type CommandContext = {
    projectRootPath: string
    task?: Task
    sourceBranch?: string
    username: string
    commandQueue: string[]
    history: History[]
}