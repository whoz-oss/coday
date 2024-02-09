type Task = {
    description: string
    data?: any
}

export type CommandContext = {
    projectRootPath: string
    task?: Task
    sourceBranch?: string
    username: string
}