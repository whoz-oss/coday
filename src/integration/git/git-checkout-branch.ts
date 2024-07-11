import {runBash} from "../../function/run-bash"
import {Interactor} from "../../model/interactor"

export const gitCheckoutBranch = async ({branchName, root, interactor}: {
  branchName: string,
  root: string,
  interactor: Interactor
}): Promise<string> => {
  const command = `git fetch --all && git checkout ${branchName}`
  return await runBash({
    command,
    root,
    interactor
  })
}
