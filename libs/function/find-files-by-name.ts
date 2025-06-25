import { Interactor } from '../model'
import { glob } from 'glob'

type FindFilesInput = {
  text: string
  path?: string
  root: string
  interactor?: Interactor
  timeout?: number
  limit?: number
}

const defaultTimeout = 5000

export const findFilesByName = async ({ text, path, root, interactor, timeout, limit }: FindFilesInput) => {
  // need to prevent double slashes
  const tweakedPath = path?.startsWith('/') ? path.substring(1) : path

  const expression = `${path ? tweakedPath + '/' : ''}**/*${text}*`

  const results = await glob(expression, {
    cwd: root,
    absolute: false,
    dotRelative: false,
    follow: false,
    signal: AbortSignal.timeout(timeout || defaultTimeout),
    ignore: ['**/node_modules/**', '**/build/**'],
  })

  return !limit || results.length < limit
    ? results
    : [`Search returned too many results (${results.length}), try again with a more restrictive search.`]
}
