import { Interactor } from '@coday/model/interactor'
import { runBash } from '@coday/function/run-bash'

const dangerKeywords = ['push', 'push -f', 'push --force', 'push --tags', 'reset', '&&']

export const git = async ({
  params,
  root,
  interactor,
}: {
  params: string
  root: string
  interactor: Interactor
}): Promise<string> => {
  const command = `git ${params}`
  const requireConfirmation = dangerKeywords.some((danger) => params.includes(danger))
  return await runBash({
    command,
    root,
    interactor,
    requireConfirmation,
  })
}
