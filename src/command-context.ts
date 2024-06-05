import {Scripts} from "./service/scripts";

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
    username: string

    /**
     * FIFO of commands to handle, can be feed by the handling of some commands
     */
    commandQueue: string[]
    history: History[]
}