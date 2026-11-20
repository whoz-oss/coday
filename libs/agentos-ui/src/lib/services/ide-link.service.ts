import { inject, Injectable } from '@angular/core'
import { buildIdeUrl, FileReference, isAbsolutePath, resolveFilePath } from './file-link'
import { USER_PREFERENCES_PORT } from './user-preferences.service'

/** Asked once, when a relative file reference is clicked and no project root is set yet. */
export const IDE_PROJECT_ROOT_PROMPT =
  'Absolute path of the project root on this machine (used to open files in IntelliJ):'

/**
 * IdeLinkService — turns a file reference clicked in a chat message into an IDE URL. A relative path is resolved
 * against the project root of the user preferences; when none is set, it is asked for once and saved.
 */
@Injectable({ providedIn: 'root' })
export class IdeLinkService {
  private readonly preferences = inject(USER_PREFERENCES_PORT)

  /**
   * The IDE URL opening the file reference, or null when a relative reference has no project root (the prompt was
   * cancelled or left blank).
   */
  resolveIdeUrl(ref: FileReference): string | null {
    const root = isAbsolutePath(ref.path) ? '' : this.ensureProjectRoot()
    if (root === null) return null
    return buildIdeUrl(this.preferences.ideUrlTemplate(), resolveFilePath(root, ref.path), ref.line, ref.column)
  }

  private ensureProjectRoot(): string | null {
    const stored = this.preferences.ideProjectRoot()
    if (stored) return stored
    const entered = window.prompt(IDE_PROJECT_ROOT_PROMPT)?.trim() ?? ''
    if (!entered) return null
    this.preferences.setIdeProjectRoot(entered)
    return entered
  }
}
