import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms'

/** POSIX root, Windows drive (`C:\`, `C:/`) or UNC share (`\\server\share`). */
const ABSOLUTE_PATH = /^(\/|[A-Za-z]:[\\/]|\\\\)/

export function isAbsolutePath(value: string): boolean {
  return ABSOLUTE_PATH.test(value)
}

/**
 * Accepts an empty value (it means "no path") and any absolute path. The server refuses a relative one with a
 * 400, so this only brings that answer forward. [applies] lets the caller switch the check off, e.g. for a
 * read-only field whose stored value the user cannot change anyway.
 */
export function absolutePathValidator(applies: () => boolean = () => true): ValidatorFn {
  return (control: AbstractControl<string | null>): ValidationErrors | null => {
    const value = (control.value ?? '').trim()
    return !applies() || value === '' || isAbsolutePath(value) ? null : { absolutePath: true }
  }
}
