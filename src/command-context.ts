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

    /**
     * FIFO of commands to handle, can be feed by the handling of some commands
     */
    commandQueue: string[]
    history: History[]
}