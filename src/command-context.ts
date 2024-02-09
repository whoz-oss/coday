type Task = {
    description: string
    key: string
    data?: any
}

export type CommandContext = {
    projectRootPath: string
    task?: Task
    sourceBranch?: string
    username: string
}