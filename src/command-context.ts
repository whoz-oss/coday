import {Scripts} from "./service/scripts";
import {ApiName} from "./service/coday-config";

export type AssistantDescription = {
    /**
     * Should be the name of the assistant as declared in the platform.
     * Matching will be done on star of the name in lowercase but still, please put full name.
     */
    name: string

    /**
     * Short description of the assistant, especially useful for other assistants to be able to call them.
     */
    description: string

    /**
     * Should the assistant not exist, it will be created if the instructions here are supplied, with these and the default model, under the account tied to the apikey
     */
    systemInstructions?: string

    /**
     * TODO: use fields, not yet connected
     * Declare what apis the assistant will have access to **in this project** (meaning if not set in the project, will not be used even if listed here).
     */
    integrations?: ApiName[]

    /**
     * Define the model to use. Clients must have a default
     */
    model?: string

    /**
     * Taken from Openai temperature, define to avoid the client’s default value
     */
    temperature?: number
}

export type Project = {
    root: string
    id?: string  // Add the id property
    description?: string
    scripts?: Scripts
    assistants?: AssistantDescription[]
}

export class CommandContext {
    private commandQueue: string[] = []
    private subTaskCount: number = 0

    constructor(
        readonly project: Project,
        readonly username: string,
    ) {}

    addCommands(...commands: string[]): void {
        this.commandQueue.unshift(...commands)
    }

    getFirstCommand(): string | undefined {
        return this.commandQueue.shift()
    }

    canSubTask(callback: () => void): boolean {
        const subTaskAvailable = this.subTaskCount !== 0
        if (subTaskAvailable) {
            callback()
        }
        return subTaskAvailable
    }

    addSubTasks(...commands: string[]): boolean {
        if (this.subTaskCount !== 0) {
            if (this.subTaskCount > 0) {
                this.subTaskCount--
            }
            this.addCommands(...commands)
            return true
        }
        return false
    }

    setSubTask(value: number): void {
        this.subTaskCount = value
    }
}
