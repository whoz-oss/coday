import {Scripts} from "./service/scripts";

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
    project: {
        root: string
        description?: string
        scripts?: Scripts
    }
    task?: Task
    sourceBranch?: string
    username: string

    /**
     * FIFO of commands to handle, can be feed by the handling of some commands
     */
    commandQueue: string[]
    history: History[]
}