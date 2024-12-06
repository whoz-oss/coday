import { runBash } from '../../function/run-bash'
import { Interactor } from '../../model/interactor'

export const gitShow = async ({
  params,
  root,
  interactor,
}: {
  params: string
  root: string
  interactor: Interactor
}) => {
  return await runBash({
    command: `git show ${params || ''}`,
    root,
    interactor,
  })
}
