import { glob } from 'glob'

type FindFilesInput = {
  text: string
  path?: string
  root: string
  timeout?: number
  limit?: number
}

const defaultTimeout = 5000

export const findFilesByName = async ({ text, path, root, timeout, limit }: FindFilesInput) => {
  // need to prevent double slashes
  const tweakedPath = path?.startsWith('/') ? path.substring(1) : path

  const expression = `${path ? tweakedPath + '/' : ''}**/*${text}*`

  const results = await glob(expression, {
    cwd: root,
    absolute: false,
    dotRelative: false,
    follow: false,
    signal: AbortSignal.timeout(timeout || defaultTimeout),
    maxDepth: 5,
    ignore: [
      '**/node_modules',
      '**/node_modules/**',
      '**/build',
      '**/build/**',
      '**/dist',
      '**/dist/**',
      '**/.git',
      '**/.git/**',
      '**/.nx',
      '**/.nx/**',
      '**/.angular',
      '**/.angular/**',
      '**/coverage',
      '**/coverage/**',
      '**/tmp',
      '**/tmp/**',
      '**/out-tsc',
      '**/out-tsc/**',
    ],
  })

  return !limit || results.length < limit
    ? results
    : [`Search returned too many results (${results.length}), try again with a more restrictive search.`]
}
