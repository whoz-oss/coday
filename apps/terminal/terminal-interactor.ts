import { select } from '@inquirer/prompts'
import chalk from 'chalk'
import { Interactor } from '@coday/model/interactor'
import {
  AnswerEvent,
  ChoiceEvent,
  ErrorEvent,
  InviteEvent,
  TextEvent,
  ThinkingEvent,
  WarnEvent,
} from '@coday/shared/coday-events'
import * as readline from 'node:readline'
import { Key } from 'node:readline' // Import Key type for clarity

// Interface augmentation to acknowledge internal properties/methods used
// This helps centralize the 'any' casts or '@ts-ignore' reasons.
interface ReadlineInterfaceWithInternals extends readline.Interface {
  line: string // Allow assignment for our custom handling
  cursor: number // Allow assignment for our custom handling
  _refreshLine(): void // Acknowledge internal method
  // Add other internal properties/methods if needed
}

export class TerminalInteractor extends Interactor {
  private interactionInProgress = false
  private promptHistory: string[] = []
  private rl: ReadlineInterfaceWithInternals | null = null // Use augmented type
  private keypressListener: ((str: string, key: Key) => void) | null = null

  constructor() {
    super()
    this.events.subscribe((event) => {
      // ... (rest of constructor remains the same) ...
      if (event instanceof TextEvent) {
        this.displayFormattedText(event.text, event.speaker)
      }
      if (event instanceof WarnEvent) {
        console.warn(`${chalk.yellow('⚠️ Warning:')} ${event.warning}\n`)
      }
      if (event instanceof AnswerEvent && !this.interactionInProgress) {
        this.displayFormattedText(event.answer, event.invite || 'Response')
      }
      if (event instanceof ErrorEvent) {
        const errorMessage = event.error instanceof Error ? event.error.message : String(event.error)
        console.error(chalk.red(`\n❌ Error: ${errorMessage}\n`))
      }
      if (event instanceof InviteEvent) {
        this.handleInviteEvent(event)
      }
      if (event instanceof ChoiceEvent) {
        this.handleChoiceEvent(event)
      }
      if (event instanceof ThinkingEvent) {
        process.stdout.write(chalk.blue('.'))
      }
    })

    process.on('SIGINT', () => this.cleanupAndExit())
    process.on('SIGTERM', () => this.cleanupAndExit())
  }

  private displayFormattedText(text: string, speaker?: string): void {
    // ... (remains the same) ...
    readline.clearLine(process.stdout, 0)
    readline.cursorTo(process.stdout, 0)

    const formattedText = speaker ? `\n${chalk.black.bgWhite(` ${speaker} `)} ${text}\n` : `${text}\n`
    console.log(formattedText)
  }

  /**
   * Setup readline interface with history and multi-line support using keypress listener.
   * NOTE: This function directly manipulates internal 'line' and 'cursor' properties
   * and uses the internal '_refreshLine' method to achieve custom multi-line editing
   * and history behavior, bypassing TypeScript's read-only checks as the public API
   * lacks sufficient control for this purpose.
   */
  private setupReadlineInterface(prompt: string, defaultValue?: string): Promise<string> {
    this.cleanupReadline()

    return new Promise((resolve, reject) => {
      try {
        // Cast to our augmented interface acknowledging internal access
        this.rl = readline.createInterface({
          input: process.stdin,
          output: process.stdout,
          prompt: prompt,
          //history: wn cannot use the native history behavior while maintaining multiline
          //         prompts (otherwise we can't navigate within these lines)
          crlfDelay: Infinity,
          tabSize: 4,
        }) as ReadlineInterfaceWithInternals
      } catch (error) {
        return reject(error)
      }

      let currentHistoryIndex = -1
      let originalInput = ''
      let isSubmitting = false

      const cleanupAndResolve = (answer: string) => {
        // ... (rest of function remains the same) ...
        if (isSubmitting) return
        isSubmitting = true

        this.cleanupReadline()

        const trimmedAnswer = answer.trim()
        if (trimmedAnswer) {
          if (this.promptHistory.length === 0 || this.promptHistory[this.promptHistory.length - 1] !== trimmedAnswer) {
            this.promptHistory.push(trimmedAnswer)
            // Optional: Limit history size
            // if (this.promptHistory.length > 50) {
            //   this.promptHistory.shift();
            // }
          }
        }
        resolve(answer)
      }

      this.keypressListener = (str: string, key: Key) => {
        if (!this.rl || isSubmitting) return

        const currentLine = this.rl.line // Read is fine
        const cursor = this.rl.cursor // Read is fine

        // --- Alt+Enter (Meta+Return) for Newline ---
        if (key && key.meta && key.name === 'return') {
          const beforeCursor = currentLine.slice(0, cursor)
          const afterCursor = currentLine.slice(cursor)
          // NOTE: Direct assignment bypasses TS read-only restriction
          this.rl.line = `${beforeCursor}\n${afterCursor}`
          this.rl.cursor = cursor + 1
          this.rl._refreshLine() // Use internal refresh
          return // Prevent default handling
        }

        // --- Standard Enter ---
        if (key && key.name === 'return' && !key.meta && !key.ctrl && !key.shift) {
          // Let the 'line' event handle submission
          return
        }

        // --- Up Arrow ---
        if (key && key.name === 'up' && !key.meta && !key.ctrl && !key.shift) {
          const { lineIndex, positionInLine } = this.getCursorPositionInfo(currentLine, cursor)

          if (lineIndex > 0) {
            // --- Multi-line Navigation Up ---
            // const prevLine = lines[lineIndex - 1]; // Unused variable removed
            const newCursor = this.calculateNewCursorPos(currentLine, lineIndex - 1, positionInLine)
            // NOTE: Direct assignment bypasses TS read-only restriction
            this.rl.cursor = newCursor
            this.rl._refreshLine()
            return // Prevent history navigation
          } else {
            // --- History Navigation Up ---
            if (this.promptHistory.length === 0) return

            if (currentHistoryIndex === -1) {
              originalInput = currentLine
              currentHistoryIndex = this.promptHistory.length - 1
            } else if (currentHistoryIndex > 0) {
              currentHistoryIndex--
            } else {
              return
            }

            // NOTE: Direct assignment bypasses TS read-only restriction
            const historyLine = this.promptHistory[currentHistoryIndex]
            this.rl.line = historyLine
            this.rl.cursor = historyLine.length
            this.rl._refreshLine()
            return
          }
        }

        // --- Down Arrow ---
        if (key && key.name === 'down' && !key.meta && !key.ctrl && !key.shift) {
          const lines = currentLine.split('\n')
          const { lineIndex, positionInLine } = this.getCursorPositionInfo(currentLine, cursor)

          if (lineIndex < lines.length - 1) {
            // --- Multi-line Navigation Down ---
            // const nextLine = lines[lineIndex + 1]; // Unused variable removed
            const newCursor = this.calculateNewCursorPos(currentLine, lineIndex + 1, positionInLine)
            // NOTE: Direct assignment bypasses TS read-only restriction
            this.rl.cursor = newCursor
            this.rl._refreshLine()
            return // Prevent history navigation / moving past input
          } else {
            // --- History Navigation Down ---
            if (currentHistoryIndex === -1) {
              return
            }

            let historyLine: string
            if (currentHistoryIndex < this.promptHistory.length - 1) {
              currentHistoryIndex++
              historyLine = this.promptHistory[currentHistoryIndex]
            } else {
              currentHistoryIndex = -1
              historyLine = originalInput
            }
            // NOTE: Direct assignment bypasses TS read-only restriction
            this.rl.line = historyLine
            this.rl.cursor = historyLine.length
            this.rl._refreshLine()
            return
          }
        }

        // Reset history navigation if user types anything other than arrows/enter
        if (currentHistoryIndex !== -1 && key && !['up', 'down', 'return'].includes(key.name || '')) {
          currentHistoryIndex = -1
          originalInput = ''
        }
      }

      // Enable raw mode and attach listener
      if (process.stdin.isTTY) {
        process.stdin.setRawMode(true)
      }
      readline.emitKeypressEvents(process.stdin)
      process.stdin.on('keypress', this.keypressListener)

      // Handle the 'line' event for submission
      this.rl.on('line', (input) => {
        cleanupAndResolve(input)
      })

      // Handle 'close' event for cleanup if interface closes unexpectedly
      this.rl.on('close', () => {
        this.cleanupReadline()
      })

      // Display initial prompt and default value
      this.rl.prompt()
      if (defaultValue) {
        this.rl.write(defaultValue)
      }
    })
  }

  // Helper to get cursor line index and position within that line
  private getCursorPositionInfo(text: string, cursor: number): { lineIndex: number; positionInLine: number } {
    // ... (remains the same) ...
    const textBeforeCursor = text.slice(0, cursor)
    const linesBeforeCursor = textBeforeCursor.split('\n')
    const lineIndex = linesBeforeCursor.length - 1
    const positionInLine = linesBeforeCursor[lineIndex].length
    return { lineIndex, positionInLine }
  }

  // Helper to calculate new cursor position when moving vertically
  private calculateNewCursorPos(text: string, targetLineIndex: number, desiredPositionInLine: number): number {
    // ... (remains the same) ...
    const lines = text.split('\n')
    const targetLine = lines[targetLineIndex]
    const targetPosition = Math.min(desiredPositionInLine, targetLine.length)

    let newCursor = 0
    for (let i = 0; i < targetLineIndex; i++) {
      newCursor += lines[i].length + 1 // +1 for the newline character
    }
    newCursor += targetPosition
    return newCursor
  }

  handleInviteEvent(event: InviteEvent): void {
    // ... (remains the same) ...
    if (this.interactionInProgress) {
      console.warn(chalk.yellow('Warning: Interaction already in progress. Ignoring new invite.'))
      return
    }
    this.interactionInProgress = true

    readline.clearLine(process.stdout, 0)
    readline.cursorTo(process.stdout, 0)

    const prompt = `\n${chalk.black.bgWhite(` ${event.invite} `)} : `

    this.setupReadlineInterface(prompt, event.defaultValue)
      .then((answer: string) => {
        this.sendEvent(event.buildAnswer(answer))
      })
      .catch((error) => {
        const errorMessage = error instanceof Error ? error.message : String(error)
        console.error(chalk.red(`\n❌ Input Error: ${errorMessage}\n`))
      })
      .finally(() => {
        this.interactionInProgress = false
      })
  }

  handleChoiceEvent(event: ChoiceEvent): void {
    // ... (remains the same) ...
    if (this.interactionInProgress) {
      console.warn(chalk.yellow('Warning: Interaction already in progress. Ignoring new choice.'))
      return
    }
    this.interactionInProgress = true

    readline.clearLine(process.stdout, 0)
    readline.cursorTo(process.stdout, 0)

    const { options, invite, optionalQuestion } = event
    select({
      message: optionalQuestion ? `${optionalQuestion}\n${chalk.bold(invite)}:` : `${chalk.bold(invite)}:`,
      choices: options.map((option) => ({
        name: option,
        value: option,
      })),
    })
      .then((answer: string) => {
        this.sendEvent(event.buildAnswer(answer))
      })
      .catch((error) => {
        if (error?.message?.includes('User force closed')) {
          console.log(chalk.yellow('\nChoice cancelled.'))
        } else {
          const errorMessage = error instanceof Error ? error.message : String(error)
          console.error(chalk.red(`\n❌ Choice Error: ${errorMessage}\n`))
        }
      })
      .finally(() => (this.interactionInProgress = false))
  }

  /**
   * Cleans up the readline interface and associated resources.
   */
  private cleanupReadline(): void {
    // ... (remains the same) ...
    if (this.keypressListener && process.stdin.listenerCount('keypress') > 0) {
      process.stdin.removeListener('keypress', this.keypressListener)
      this.keypressListener = null
    }

    if (this.rl) {
      this.rl.close()
      this.rl = null
    }

    if (process.stdin.isTTY && process.stdin.listenerCount('keypress') === 0) {
      try {
        process.stdin.setRawMode(false)
      } catch (e) {
        // Ignore
      }
    }
  }

  /**
   * General cleanup for the interactor.
   */
  cleanup(): void {
    // ... (remains the same) ...
    console.log('\nCleaning up terminal interactor...')
    this.cleanupReadline()
  }

  /**
   * Cleanup and exit the process.
   */
  cleanupAndExit(): void {
    // ... (remains the same) ...
    this.cleanup()
    process.exit(0)
  }
}
