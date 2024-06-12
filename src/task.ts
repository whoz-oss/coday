export type Data = {[key: string]: string }

export type ICommand = {
    getCommandToStart(): string
    getData(key: string): string | undefined
    setData(key: string, value: string): void
}

export class Command implements ICommand {
    constructor(private readonly command: string) {}

    data = new Map<string, string>()

    getData(key: string): string | undefined {
        return this.data.get(key)
    }
    setData(key: string, value: string): void {
        this.data.set(key, value)
    }
    getCommandToStart(): string {
        return this.command
    }


}