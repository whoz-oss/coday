import { Interactor } from '../../model'
import { exec } from 'child_process'
import { promisify } from 'util'

const execAsync = promisify(exec)

type FindFilesByTextInput = {
  text: string
  path?: string
  root: string
  fileTypes?: string[]
  interactor: Interactor
  timeout?: number
}

const defaultTimeout = 10000
const defaultMaxBuffer = 10 * 1024 * 1024 // 10MB

const escapeRegExp = (string: string) => {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') // $& means the whole matched string
}

export const findFilesByText = async ({
  text,
  path,
  root,
  fileTypes = [],
  interactor,
  timeout = defaultTimeout,
}: FindFilesByTextInput): Promise<string> => {
  const escapedText = escapeRegExp(text)
  const fileTypePattern = fileTypes.length > 0 ? fileTypes.map((type) => `-g "*.${type}"`).join(' ') : ''
  const searchPath = path ?? '.'
  const searchCommand = `rg "${escapedText}" ${searchPath} ${fileTypePattern} --color never -l`

  interactor.displayText(`\nExecuting search command: ${searchCommand}`)

  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`Search timed out after ${timeout} milliseconds`))
    }, timeout)

    execAsync(searchCommand, { cwd: root, maxBuffer: defaultMaxBuffer })
      .then(({ stdout }) => {
        clearTimeout(timer)
        resolve(stdout)
      })
      .catch(({ code, stderr }) => {
        clearTimeout(timer)
        if (code === 1) {
          // Ripgrep returns code 1 when no matches found, treat as empty result
          resolve('No match found')
        } else {
          console.error(stderr)
          reject(`Error: ${stderr}`)
        }
      })
  })
}
