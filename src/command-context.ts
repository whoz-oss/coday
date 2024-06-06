import {Scripts} from "./service/scripts";

type History = {
    command: string
    response: string
}

export type Project = {
    root: string
    description?: string
    scripts?: Scripts
}

export class CommandContext {
    private commandQueue: string[] = []

    constructor(
        readonly project:Project,
        readonly username: string,
    ) {

    }

    addCommands(...commands: string[]): void {
        this.commandQueue.unshift(...commands)
    }

    getFirstCommand(): string | undefined {
        return this.commandQueue.shift()
    }
}
