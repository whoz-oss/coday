export interface Interactor {
    promptText(invite: string, defaultText?: string): string
    chooseOption(options: string[], question: string, invite?: string): string
    displayText(text: string): void
    warn(warning: string): void
    error(error: unknown): void
    addSeparator(): void
}