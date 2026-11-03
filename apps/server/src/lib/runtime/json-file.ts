import * as fs from 'fs'
import * as path from 'path'

/** Read and parse a JSON file; undefined when absent or unparseable (never throws). */
export function readJsonFile<T>(file: string): T | undefined {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8')) as T
  } catch {
    return undefined
  }
}

/** Write JSON through a temp file + rename so readers never see a half-written file. */
export function writeJsonFile(file: string, data: unknown, mode?: number): void {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const tmp = `${file}.${process.pid}.tmp`
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\n', mode === undefined ? undefined : { mode })
  if (mode !== undefined) fs.chmodSync(tmp, mode)
  fs.renameSync(tmp, file)
}
