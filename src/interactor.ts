export interface Interactor {
  promptText(invite: string, defaultText?: string): Promise<string>
  chooseOption(
    options: string[],
    question: string,
    invite?: string,
  ): Promise<string>
  displayText(text: string, speaker?: string): void
  warn(warning: string): void
  error(error: unknown): void
  addSeparator(): void
}
